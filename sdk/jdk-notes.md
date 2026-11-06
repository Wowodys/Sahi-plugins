# JDKs on this machine — which one for what

## The short version

| Task | JVM | Why |
|---|---|---|
| **Building plugins** (`gradle build`) | Run Gradle with `JAVA_HOME=C:\Users\sahij\AppData\Local\RuneLite\jre` (Temurin **17** JRE); compile via the **JDK 11 toolchain** (Temurin 11.0.28, auto-detected) | Gradle 9.1 needs a Java 17+ JVM to run, but the RuneLite-bundled 17 is a JRE without `javac` — so Gradle runs on it and compilation is delegated to the JDK 11 toolchain. `templates/skeleton-plugin/build.gradle` already sets this up (`java.toolchain.languageVersion = 11`, `options.release = 11`) |
| **Running `java -jar cfr.jar`, ConstDump, EnumDump** | any; the PATH `java` (Temurin 11.0.28 JDK) works | these tools only need Java 8+ |
| **The RuneLite/Kuri client runtime** | `C:\Users\sahij\AppData\Local\RuneLite\jre` (Temurin 17.0.19) | the client is launched by its own launcher with this JRE — never change it |
| **Capturing keys (javaagent)** | the client's own JRE (17) | the agent rides in the client JVM; build the agent jar with any JDK (its sources target Java 8+) |

## Details and gotchas

1. **RuneLite/Kuri targets Java 11.** Both `runelite-api-1.13.6-runtime.jar` and
   `client-1.13.6.jar` are class-file major 55 (Java 11) — verified from the
   jars themselves. Plugin jars for Plugin Hub should therefore compile with
   `--release 11` (or a toolchain 11), not newer. Kuri's own decrypted plugin
   jars enforce ≤ class-file 55 as well (the framework's archive validator
   rejects class files newer than Java 11).
2. **Gradle's JVM vs the compile JVM are different things.** Gradle 9.1 cannot
   run on Java 11 (needs 17+), and the only local 17 is a **JRE** — so
   `gradle build` with `JAVA_HOME` pointing elsewhere fails with
   *"Toolchain installation '...\RuneLite\jre' does not provide the required
   capabilities: [JAVA_COMPILER]"*. The toolchain block in the template
   build.gradle is the fix; it auto-detects the Temurin 11 JDK at
   `C:\Program Files\Eclipse Adoptium\jdk-11.0.28.6-hotspot`.
3. **If toolchain detection ever fails**, force it:
   ```
   gradle -Dorg.gradle.java.installations.paths="C:\Program Files\Eclipse Adoptium\jdk-11.0.28.6-hotspot" build
   ```
   (auto-download of toolchains is off; nothing is fetched from the network).
4. **The first `gradle build` can appear to hang** with a daemon that never
   comes up in restricted environments. Use `--no-daemon` (the template docs
   use it throughout; adds ~5 s per build and always terminates).
5. **Do not compile against the Kuri framework jar.** `sdk/jars/kuri/` is for
   running analysis tools against Kuri's decrypted jars only — plugins must
   never link `com.kuri.*`.
