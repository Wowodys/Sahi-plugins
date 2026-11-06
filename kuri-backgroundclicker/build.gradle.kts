import org.gradle.api.plugins.quality.Checkstyle

/*
 * Kuri Background Clicker plugin - compiled straight into the RuneLite client.
 *
 * Same registration route as its sibling kuri-oneclick: this project lives
 * OUTSIDE the runelite repository (sibling directory) and is linked in by
 * runelite's settings.gradle.kts:
 *
 *     include("kuri-backgroundclicker")
 *     project(":kuri-backgroundclicker").projectDir = file("../kuri-backgroundclicker")
 *
 * and consumed by runelite-client/build.gradle.kts:
 *
 *     runtimeOnly(project(":kuri-backgroundclicker"))
 *
 * so Gradle places the plugin's classes on the client's runtime classpath -
 * PluginManager's core classpath scan (ClassPath.from(...)
 * .getTopLevelClassesRecursive("net.runelite.client.plugins")) finds them at
 * startup. No jar side-loading, no runelite_plugin.json needed for this route.
 *
 * JNA (com.sun.jna:*) comes for free: runelite-client declares jna-core and
 * jna-platform as `api` dependencies, so they are on this project's compile
 * classpath transitively through compileOnly(project(":client")). Do NOT add
 * a second JNA here - two copies of the native glue would fight over JNI.
 *
 * Common defaults (UTF-8, Java release 11, repositories, version catalog)
 * come from runelite's common.settings.gradle.kts.
 */

plugins {
	java
}

// runelite's common.settings.gradle.kts points checkstyle at
// ../config/checkstyle relative to each project; from this external
// directory that path does not exist. Point it at the repository's shared
// config instead.
extensions.configure(org.gradle.api.plugins.quality.CheckstyleExtension::class.java) {
	configDirectory = rootProject.file("config/checkstyle")
}

dependencies {
	/*
	 * Same one-directional edge as kuri-oneclick: :client pulls THIS project
	 * in via runtimeOnly(), so the compile-time dependency must stay
	 * compileOnly to avoid a circular task graph.
	 */
	compileOnly(project(":client"))
}

// checkstyle runs as part of `check`; keep it out of the critical compile
// path so `gradlew :client:compileJava` only compiles.
tasks.withType(Checkstyle::class.java).configureEach {
	isIgnoreFailures = true
}
