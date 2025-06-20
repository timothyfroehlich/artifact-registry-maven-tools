# Project Overview

This project provides tools for authenticating and interacting with Maven repositories hosted on Google Cloud Artifact Registry. It includes a Maven Wagon and a Gradle Plugin.

## Subprojects

*   **`artifactregistry-auth-common`**: A shared library for authentication logic used by both the wagon and the plugin.
*   **`artifactregistry-gradle-plugin`**: A Gradle plugin for using Artifact Registry repositories.
*   **`artifactregistry-maven-wagon`**: A Maven Wagon implementation for accessing Artifact Registry.

## Authentication

Authentication is handled via Google Application Default Credentials or the `gcloud` SDK. The `DefaultCredentialProvider` in `artifactregistry-auth-common` first tries to get credentials from `gcloud` and falls back to Application Default Credentials.

## `artifactregistry-auth-common`

This subproject provides the core authentication logic.

*   **`CredentialProvider`**: An interface for providing credentials.
*   **`DefaultCredentialProvider`**: The default implementation of `CredentialProvider`. It first tries to get credentials from `gcloud` and falls back to Application Default Credentials.
*   **`GcloudCredentials`**: A `GoogleCredentials` implementation that gets credentials by shelling out to the `gcloud` command-line tool.
*   **`CommandExecutor`**: An interface for executing shell commands.
    *   **`ProcessBuilderCommandExecutor`**: The implementation used by the Maven wagon.
    *   **`ProviderFactoryCommandExecutor`**: The implementation used by the Gradle plugin.

## `artifactregistry-gradle-plugin`

This subproject contains the Gradle plugin.

*   **`ArtifactRegistryGradlePlugin`**: The main plugin class. It registers a `PasswordCredentials` object that uses the `artifactregistry-auth-common` library to get an OAuth2 access token. It then configures any repositories with a URL scheme of `artifactregistry` to use HTTPS and the aforementioned credentials.

## `artifactregistry-maven-wagon`

This subproject contains the Maven wagon.

*   **`ArtifactRegistryWagon`**: The main wagon class. It uses the `artifactregistry-auth-common` library to get credentials and then uses them to make HTTP requests to the Artifact Registry API. It handles `get` and `put` operations.

## Build System

The project is built with Gradle. The root `build.gradle` file defines the overall project structure, dependencies, and publishing information.

## Test Project

The `sample-composite-project` contains a Gradle composite project that can be used to test the use of the plugin in an integration test. It includes `sample-composite-project/load-AR-plugin-from-project.init.gradle.kts`, which is copied to the user's `~/.gradle/init.d/` directory. It will find and load `sample-composite-project/load-AR-plugin.init.gradle.kts`, which will apply the Artifact Registry plugin during init.

### Testing the sample project with modifications to the plugin

The plugin must be fully built to be tested. To test local changes, you can publish the plugin and its dependencies to a local repository and then run the sample project.

1.  **Publish the plugin and its dependencies to a local repository:**

    ```bash
    ./gradlew :artifactregistry-gradle-plugin:publish \
        :artifactregistry-auth-common:publish
    ```

2.  **Run the sample project with the local repository:**

    ```bash
    cd sample-composite-project
    ./gradlew build --init-script load-AR-plugin.init.gradle.kts
    ```
