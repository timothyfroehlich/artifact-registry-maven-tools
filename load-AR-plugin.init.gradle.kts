
initscript {
  repositories {

    // This script is meant to test loading of the Artifact Registry plugin
    // with local changes. After making changes to the plugin,
    // run `./gradlew publishToMavenLocal` in the root of the repo
    // to publish the plugin to your local Maven repository.
    mavenLocal()

    // Uncomment the next line to use the plugin from the Gradle Plugin Portal
    // gradlePluginPortal()

  }
  dependencies {
    classpath("com.google.cloud.artifactregistry:artifactregistry-gradle-plugin:+")

  }
}
apply<com.google.cloud.artifactregistry.gradle.plugin.ArtifactRegistryGradlePlugin>()
