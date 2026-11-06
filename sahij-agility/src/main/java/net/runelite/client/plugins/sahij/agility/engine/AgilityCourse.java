/*
 * Copyright (c) 2026, sahij
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.sahij.agility.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The course definition - the EXACT spec data, verbatim, in one place.
 * Nothing in this class is computed or guessed: tiles, arrival points,
 * chunk ids, region id and object ids are the documented sequence values.
 * The world layer cross-checks the documented chunk ids (target-tile and
 * arrival) against coordinate arithmetic once per login (fail visible);
 * this class itself stays pure data so the engine can be exercised without
 * a client.
 *
 * The documented sequence (region 10806 throughout). Each step carries its
 * explicit, deterministic ARRIVAL point - the exact tile (x, y, plane) the
 * player lands on when the step's action completes; the engine advances
 * strictly on that coordinate:
 *
 *  1. Walk here  - tile (2729, 3489, 0), chunk 698804
 *                 arrival (2729, 3489, 0), chunk 698804
 *  2. Climb-up   - Wall    14927, tile (2729, 3489, 0), chunk 698804
 *                 arrival (2729, 3491, 3), chunk 698804  <- the plane 0 -> 3 climb transition
 *  3. Jump       - Gap     14928, tiles 2720..2723 x 3491..3493 z3 (12), chunk 696756, plane-drop reset
 *                 arrival (2713, 3494, 2), chunk 694708
 *  4. Cross      - Tightrope 14932, tile (2710, 3489, 2), chunk 692660, plane-drop reset
 *                 arrival (2710, 3480, 2), chunk 692659
 *  5. Jump       - Gap     14929, tiles 2709..2711 x 3476 z2 (3), chunk 692658, plane-drop reset
 *                 arrival (2710, 3472, 3), chunk 692658
 *  6. Jump       - Gap     14930, tiles 2701..2703 x 3469 z3 (3), chunk 690609
 *                 arrival (2702, 3465, 2), chunk 690609
 *  7. Jump       - Edge    14931, tiles 2703 x 3464..3465 z2 (2), chunk 690609
 *                 arrival (2704, 3464, 0), chunk 692657
 *
 * Steps 3, 5, 6 and 7 carry multiple tiles; their array index is the
 * primary-monitor screen-zone index (see AgilityStep's ordering contract and
 * world/ScreenZoneMapper). Steps 1 and 2 share the same target tile on
 * purpose: the walk delivers the player to the wall, and the climb is the
 * action taken standing there.
 */
public final class AgilityCourse
{
	/** Documented region id of the whole course (fail-visible identity check). */
	public static final int COURSE_REGION_ID = 10806;

	/** Number of steps in the documented sequence. */
	public static final int STEP_COUNT = 7;

	private final List<AgilityStep> steps;

	private AgilityCourse(List<AgilityStep> steps)
	{
		this.steps = Collections.unmodifiableList(steps);
	}

	/** The documented 7-step sequence. */
	public static AgilityCourse create()
	{
		List<AgilityStep> steps = new ArrayList<>(STEP_COUNT);

		// Step 1: Walk here (single tile); arrival = the start tile itself.
		steps.add(new AgilityStep(
			1, "Walk here", "", AgilityStep.WALK_OBJECT_ID,
			false, 698804, COURSE_REGION_ID,
			tiles(tile(2729, 3489, 0)),
			tile(2729, 3489, 0), 698804));

		// Step 2: Climb-up on Wall 14927 (single tile). Arrival is the
		// wall-top landing on plane 3 - the explicit plane 0 -> 3 climb
		// transition; no heuristic anywhere.
		steps.add(new AgilityStep(
			2, "Climb-up", "Wall", 14927,
			false, 698804, COURSE_REGION_ID,
			tiles(tile(2729, 3489, 0)),
			tile(2729, 3491, 3), 698804));

		// Step 3: Jump Gap 14928 - tiles 2720..2723 x 3491..3493 z3 (12).
		// The spec spells the verb "jump"; display form is "Jump" and the
		// object-composition match is case-insensitive, so both denote the
		// same action variant.
		steps.add(new AgilityStep(
			3, "Jump", "Gap", 14928,
			true, 696756, COURSE_REGION_ID,
			rangeTiles(2720, 2723, 3491, 3493, 3),
			tile(2713, 3494, 2), 694708));

		// Step 4: Cross Tightrope 14932 (single tile); arrival = the rope's
		// far end.
		steps.add(new AgilityStep(
			4, "Cross", "Tightrope", 14932,
			true, 692660, COURSE_REGION_ID,
			tiles(tile(2710, 3489, 2)),
			tile(2710, 3480, 2), 692659));

		// Step 5: Jump Gap 14929 - tiles 2709..2711 x 3476 z2 (3); arrival
		// lands up a level, on plane 3.
		steps.add(new AgilityStep(
			5, "Jump", "Gap", 14929,
			true, 692658, COURSE_REGION_ID,
			rangeTiles(2709, 2711, 3476, 3476, 2),
			tile(2710, 3472, 3), 692658));

		// Step 6: Jump Gap 14930 - tiles 2701..2703 x 3469 z3 (3).
		steps.add(new AgilityStep(
			6, "Jump", "Gap", 14930,
			false, 690609, COURSE_REGION_ID,
			rangeTiles(2701, 2703, 3469, 3469, 3),
			tile(2702, 3465, 2), 690609));

		// Step 7: Jump Edge 14931 - tiles 2703 x 3464..3465 z2 (2); arrival
		// is the ground-level landing that closes the lap.
		steps.add(new AgilityStep(
			7, "Jump", "Edge", 14931,
			false, 690609, COURSE_REGION_ID,
			rangeTiles(2703, 2703, 3464, 3465, 2),
			tile(2704, 3464, 0), 692657));

		if (steps.size() != STEP_COUNT)
		{
			throw new IllegalStateException("course definition has " + steps.size()
				+ " steps, expected " + STEP_COUNT);
		}
		return new AgilityCourse(steps);
	}

	/** The steps, index 0 = step 1. Unmodifiable. */
	public List<AgilityStep> getSteps()
	{
		return steps;
	}

	public AgilityStep step(int index)
	{
		return steps.get(index);
	}

	/** Index of the last step (step 7), i.e. {@link #STEP_COUNT} - 1. */
	public int lastStepIndex()
	{
		return STEP_COUNT - 1;
	}

	private static StepTile tile(int x, int y, int plane)
	{
		return new StepTile(x, y, plane);
	}

	private static List<StepTile> tiles(StepTile... ts)
	{
		List<StepTile> list = new ArrayList<>(ts.length);
		Collections.addAll(list, ts);
		return list;
	}

	/**
	 * Expands an inclusive rectangular tile range in the canonical screen-zone
	 * order: x ascending in the outer loop, y ascending in the inner loop.
	 */
	private static List<StepTile> rangeTiles(int xFrom, int xTo, int yFrom, int yTo, int plane)
	{
		if (xTo < xFrom || yTo < yFrom)
		{
			throw new IllegalArgumentException("inverted tile range "
				+ xFrom + ".." + xTo + " x " + yFrom + ".." + yTo);
		}
		List<StepTile> list = new ArrayList<>((xTo - xFrom + 1) * (yTo - yFrom + 1));
		for (int x = xFrom; x <= xTo; x++)
		{
			for (int y = yFrom; y <= yTo; y++)
			{
				list.add(tile(x, y, plane));
			}
		}
		return list;
	}
}
