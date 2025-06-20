// In ~/.gradle/init.d/

// This script runs for every Gradle build on this machine.
// Its purpose is to find and apply a project-specific init script.

 to the root directory
val projectInitScriptFile = file("${gradle.startParameter.currentDir}/load-AR-plugin.init.gradle.kts")

if (projectInitScriptFile.exists()) {
    println("==> Found Artifact Registry init script, applying it...")
    apply { from(projectInitScriptFile) }
}
