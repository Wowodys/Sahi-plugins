# 01 — Design Philosophy: One Click, One Bounded Sequence

> This is the rulebook that makes a "one-click" plugin behave like a player
> instead of a bot. Everything in `templates/skeleton-plugin` and the case
> study ([06-case-study-construction.md](06-case-study-construction.md)) follows
> it; every rule below maps to a concrete implementation there.

The genre: the player performs a single, ordinary-looking click; the plugin
completes a task around it. The difference between that and a bot is not the
feature list — it is *who drives, and what each driver is allowed to observe*.
These six rules are the whole discipline.

---

## Rule 1 — One real click = one bounded, event-driven sequence

A single ordinary click arms the plugin. From there the plugin **reacts**:
object spawns, inventory changes, widget loads, varbit flips, chat messages.
It never advances on wall-clock timers alone, and there are no fixed-delay
step chains ("click, wait 1.2s, click again" is banned).

- Concretely: when the armed click happens, the plugin stores *intent* (a
  target, a pending requirement list). The next step happens in the handler of
  the event that *confirms* the previous step.
- The only legitimate waits are re-validation waits: "on the next GameTick,
  check whether the condition still holds" is fine; "sleep 800 ms then
  continue" is not. Ticks are the game's own metronome — using them for
  *checking* is observation, not action.
- The sequence is **bounded**: it ends at a server-confirmed terminal state or
  a reset trigger, whichever comes first. There is no "try again" loop.

## Rule 2 — Operate only through the client's own UI layer

Every outbound action must be a packet the client would send during normal
play, triggered the way normal play triggers it:

- Create or influence **menu entries** (`client.getMenu().createMenuEntry(-1)`
  with real `MenuAction` types and real targets), click **widgets** through
  the same code path a user click uses, and navigate dialogs by their
  continue/options widgets.
- **Never craft, modify, or inject packets.** If you find yourself writing a
  byte buffer, stop — you are writing a bot.
- **Never touch memory or reflect into obfuscated client internals.** Public
  RuneLite API only (`net.runelite.api.*` + the client-facing
  `net.runelite.client.*` services). The Kuri case study needed no hook-catalog
  internals at all; if a feature seems to require them, redesign it.
- The self-check: *could this exact outbound byte-stream have been produced by
  a bored human clicking in the standard UI?* If not, it does not ship.

## Rule 3 — Server-confirmed progress

Every step advances only after the game itself confirms the previous step:

- the furniture **spawned** as a game object → build done,
- the inventory **changed** → materials consumed/arrived,
- the build interface **loaded** (widget group 458) → the click landed,
- the varbit **flipped** (e.g. 2176 "inside player-owned house") → location
  state changed,
- the chat message **appeared** → server text feedback.

Anomalies do not trigger retries; they trigger **abort-and-reset**: missing
materials → clear the cycle (optionally surface one chat line); unexpected
dialog → clear flags; state varbit says you left the house → wipe queues.
"Abort on anomaly" is safer *and simpler* than "retry on anomaly", because it
needs no timeout machinery and cannot desync from the server.

## Rule 4 — State hygiene: every transient flag has a natural reset trigger

No zombie states. Each flag is written down with at least one *natural* reset
— an event that would plausibly fire outside the plugin's own actions:

| Flag (construction case) | Natural reset trigger |
|---|---|
| "cycle armed" | clicked object **spawned** (server confirmed); dialog widget loaded; `WidgetClosed` 219; varbit 2176→0; Phials NPC spawn; inventory drain; specific chat error |
| servant state | varbit 6719→0 (servant dismissed); servant NPC despawn/respawn |
| fetch-pending (Phials) | `NpcSpawned` 1614 |
| can-fill mode | leaving the house (varbit 2176→0) + no empty can; "need a watering can with water" chat message |
| session stats | `GameStateChanged` → LOGIN_SCREEN |

Plus two global resets: plugin `shutDown()` and login. A flag without a
documented reset trigger does not get written. Test every trigger by hand
(enabled plugin → fire trigger → verify state cleared; no stale behavior).

## Rule 5 — Human-shaped input

- Clicks originate from **real user input or menu entries** at rates the
  activity allows. The plugin never introduces action rates faster than the
  game's own pacing: a construction build takes as long as the server takes —
  the next action happens on the *spawn event*, which is inherently server-paced.
- Menu entries the plugin adds look like menu entries: one per context, stable
  position (pinned to the bottom via `PostMenuSort`), no flickering, no
  entries that appear only for one frame or mutate existing entries' targets.
- No synthetic mouse movement, no click coordinates injection, no key
  flooding. The Kuri plugins drive at most one outbound action per armed
  cycle per tick — a rate no observer can distinguish from the player's own
  clicking.
- Reactions are allowed to be *fast* (event handlers run on the client
  thread) but never *simultaneous*: one pending outbound action at a time.

## Rule 6 — Fail visible

Silent loops are the enemy: a plugin that quietly re-tries forever looks
exactly like a bot to the server and is unusable to the player. Instead:

- every abort surfaces once — an overlay line is the minimum; a single chat
  message is acceptable for fatal config problems;
- the overlay always shows current state (armed/waiting/materials/idle) so the
  player can see what the plugin thinks is happening;
- when in doubt, the plugin stops and says why, rather than guessing.

---

## Threat model — what an observer actually sees

Behavioral detection (server-side or client-scan side) has no access to your
plugin's code or Guice graph. It observes the **stream of game-visible
events**:

| Observable | Bot signature | This design's signature |
|---|---|---|
| Packet cadence | metronomic, wall-clock spaced, invariant across sessions | each packet follows a server-event with natural jitter (spawn/render/tick arrival), server-paced by construction |
| Action rates | beyond the activity's ceiling, sustained for hours | rate = the activity's own rate; bounded sequences end (materials out, reset trigger) |
| Reaction times | constant sub-tick responses to stimuli | responses ride normal event delivery; ordering is the game's own |
| Menu/UI-source flags | actions from no UI source (raw packet ids with no menu open) | every action is the client's own dispatch of a real menu/widget action with the standard UI trail |
| Content shape | same op on same target forever with impossible context | real targets, real option text, human-reachable menu positions |
| Impossible knowledge | acting on data a player cannot see (e.g. precise spawn timing pre-frame) | only public events (`GameObjectSpawned` fires when the player's client already knows) |

The reason these rules keep the observable behavior ordinary is structural,
not cosmetic: **the outbound byte-stream is the client's own**. Rule 2 makes
every packet a packet the UI would send; rules 1/3/5 make its *timing* a
function of server-paced game events rather than a local scheduler; rule 4
guarantees the plugin cannot keep acting while its assumptions are stale
(the strongest bot signature: continuing an action after the world changed).

What this model does **not** defend against: account-level statistical
profiling over weeks (session length, monotony of location), or a policy
decision that automation of any kind is disallowed for the account. No
client-side design changes those — only the human using the plugin decides
how much to run it. The plugin's job is to never *add* anything machine-shaped
to the traffic; it cannot remove the shape the user chooses to produce.

## Rule → implementation map (construction case study)

| Rule | Where it is implemented in the case study |
|---|---|
| 1. one click, bounded sequence | `MenuOptionClicked` on the injected entry arms the cycle (`b.c`); every following action fires from `GameObjectSpawned`/`WidgetLoaded`/`ItemContainerChanged` handlers; cycle ends on materials-out or reset |
| 2. client UI layer only | injected entries carry real `MenuAction`s (`GAME_OBJECT_FIFTH_OPTION`, `WIDGET_CONTINUE`) on real targets; build/remove/fetch/pay are ordinary ops; no packets, no reflection — the only client-internal API used was the platform's `Menu` facade, replaced by `client.getMenu()` in the port |
| 3. server-confirmed progress | build completion = object spawn event (identity/tile match against pending queue); material arrival = `ItemContainerChanged(93)`; dialog confirm = `WidgetLoaded`/text check; never a timeout |
| 4. state hygiene | eleven documented reset triggers (§ table above); full reset `c()` clears queues+flags+counters; varbit 2176 gates all spawn handling |
| 5. human-shaped input | menu entry pinned to bottom via `PostMenuSort`; one pending action per tick; servant wage dialog verified by its own text before paying; aborts on anomalies instead of retrying |
| 6. fail visible | aborts reset the cycle and surface state via the stats overlay (timer/XP/actions/materials); material exhaustion stops the loop rather than spinning |

See [06-case-study-construction.md](06-case-study-construction.md) for the
full walkthrough, and [04-interaction-patterns.md](04-interaction-patterns.md)
for reusable implementations of each pattern.
