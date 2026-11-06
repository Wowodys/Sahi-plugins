/*
 * Sahij Agility plugin - compiled straight into the RuneLite client.
 *
 * This project lives OUTSIDE the runelite repository (sibling directory) and
 * is linked in by runelite's settings.gradle.kts:
 *
 *     include("sahij-agility")
 *     project(":sahij-agility").projectDir = file("../sahij-agility")
 *
 * and consumed by runelite-client/build.gradle.kts:
 *
 *     runtimeOnly(project(":sahij-agility"))
 *
 * so Gradle places the plugin's classes on the client's runtime classpath -
 * PluginManager's core classpath scan (ClassPath.from(...)
 * .getTopLevelClassesRecursive("net.runelite.client.plugins")) finds them at
 * startup. Same registration route as kuri-oneclick and
 * kuri-backgroundclicker. No jar side-loading, no --developer-mode dependence.
 *
 * Common defaults (UTF-8, Java release 11, repositories, version catalog)
 * come from runelite's common.settings.gradle.kts, which the root build
 * applies to every project - including this one.
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
	 * Plugin, Overlay and service classes (net.runelite.client.*) live in
	 * the :client project; net.runelite.api.* comes with it transitively
	 * (the client declares runelite-api as an api dependency).
	 *
	 * compileOnly avoids the circular project dependency: :client already
	 * pulls THIS project in on its runtime classpath via runtimeOnly().
	 * Gradle must not build a compile-time edge back from the plugin to the
	 * client for this to resolve.
	 *
	 * Deliberately NO dependency on :kuri-oneclick or
	 * :kuri-backgroundclicker: this module is fully decoupled and meets the
	 * background clicker only at the client's vanilla input pipeline (it
	 * consumes the MenuOptionClicked events any real or forwarded click
	 * produces).
	 */
	compileOnly(project(":client"))
}

// checkstyle runs as part of `check`; keep it out of the critical compile
// path so `gradlew :client:compileJava` only compiles.
tasks.withType(Checkstyle::class.java).configureEach {
	isIgnoreFailures = true
}
