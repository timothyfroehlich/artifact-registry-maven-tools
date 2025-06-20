/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.artifactregistry.gradle.plugin;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.artifactregistry.auth.CommandExecutor;
import com.google.cloud.artifactregistry.auth.CredentialProvider;
import com.google.cloud.artifactregistry.auth.DefaultCredentialProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import javax.annotation.Nullable;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.ProjectConfigurationException;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.credentials.Credentials;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.tasks.Input;
import org.gradle.internal.authentication.DefaultBasicAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactRegistryGradlePlugin implements Plugin<Object> {

  private static final Logger logger = LoggerFactory.getLogger(ArtifactRegistryGradlePlugin.class);

  static class ArtifactRegistryPasswordCredentials implements PasswordCredentials {
    private String username;
    private String password;
    private final CommandExecutor commandExecutor;

    ArtifactRegistryPasswordCredentials(String username, String password, CommandExecutor commandExecutor) {
      this.username = username;
      this.password = password;
      this.commandExecutor = commandExecutor;
    }

    @Input
    @Override
    public String getUsername() {
      return username;
    }

    @Input
    @Override
    public String getPassword() {
      return password;
    }

    @Override
    public void setUsername(String username) {
      this.username = username;
    }

    @Override
    public void setPassword(String password) {
      this.password = password;
    }
  }

  private final CredentialProvider credentialProvider = DefaultCredentialProvider.getInstance();

  @Override
  public void apply(Object o) {
    if (o instanceof Settings) {
      applySettings((Settings) o);
    } else if (o instanceof Project) {
      applyProject((Project) o);
    } else if (o instanceof Gradle) {
      // This path is taken when the plugin is applied to an init script.
      // In this case, we can't get the provider factory right away, so we
      // have to wait until the project is evaluated.
      Gradle gradle = (Gradle) o;
      gradle.projectsEvaluated(
          new Action<Gradle>() {
            @Override
            public void execute(Gradle g) {
              g.allprojects(
                  new Action<Project>() {
                    @Override
                    public void execute(Project p) {
                      applyProject(p);
                    }
                  });
            }
          });
    }
  }

  private void applySettings(Settings settings) {
    ProviderFactory providerFactory = settings.getProviders();
    CommandExecutor commandExecutor = new ProviderFactoryCommandExecutor(providerFactory);
    ArtifactRegistryPasswordCredentials crd = createCredentials(commandExecutor);

    settings.getPluginManagement().getRepositories().all(
        r -> configureArtifactRegistryRepository(r, crd));
    settings.getDependencyResolutionManagement().getRepositories().all(
        r -> configureArtifactRegistryRepository(r, crd));
  }

  private void applyProject(Project project) {
    ProviderFactory providerFactory = project.getProviders();
    CommandExecutor commandExecutor = new ProviderFactoryCommandExecutor(providerFactory);
    ArtifactRegistryPasswordCredentials crd = createCredentials(commandExecutor);

    project.getBuildscript().getRepositories().all(
        r -> configureArtifactRegistryRepository(r, crd));
    project.getRepositories().all(
        r -> configureArtifactRegistryRepository(r, crd));

    final PublishingExtension publishingExtension =
        project.getExtensions().findByType(PublishingExtension.class);
    if (publishingExtension != null) {
      publishingExtension.getRepositories().all(
          r -> configureArtifactRegistryRepository(r, crd));
    }
  }

  @Nullable
  private ArtifactRegistryPasswordCredentials createCredentials(CommandExecutor commandExecutor) {
    try {
      GoogleCredentials credentials = (GoogleCredentials)credentialProvider.getCredential(commandExecutor);
      credentials.refreshIfExpired();
      AccessToken accessToken = credentials.getAccessToken();
      String token = accessToken.getTokenValue();
      return new ArtifactRegistryPasswordCredentials("oauth2accesstoken", token, commandExecutor);
    } catch (IOException e) {
      logger.warn("Failed to get access token from gcloud or Application Default Credentials", e);
      return null;
    }
  }

  private void configureArtifactRegistryRepository(
      ArtifactRepository repo, @Nullable ArtifactRegistryPasswordCredentials crd)
      throws ProjectConfigurationException, UncheckedIOException {
    if (!(repo instanceof DefaultMavenArtifactRepository)) {
      return;
    }
    final DefaultMavenArtifactRepository arRepo = (DefaultMavenArtifactRepository) repo;
    final URI u = arRepo.getUrl();
    if (u != null && u.getScheme() != null && u.getScheme().equals("artifactregistry")) {
      try {
        arRepo.setUrl(new URI("https", u.getHost(), u.getPath(), u.getFragment()));
      } catch (URISyntaxException e) {
        throw new ProjectConfigurationException(
            String.format("Invalid repository URL %s", u.toString()), e);
      }

      if (crd != null && shouldStoreCredentials(arRepo)) {
        arRepo.setConfiguredCredentials(crd);
        arRepo.authentication(authenticationContainer -> authenticationContainer
            .add(new DefaultBasicAuthentication("basic")));
      }
    }
  }

  // This is a shim to work around an incompatible API change in Gradle 6.6. Prior to that,
  // AbstractAuthenticationSupportedRepository#getConfiguredCredentials() returned a (possibly null)
  // Credentials object. In 6.6, it changed to return Property<Credentials>.
  //
  // Compiling this plugin against Gradle 6.5 results in a NoSuchMethodException if you run it under
  // Gradle 6.6. The same thing happens if you compile against 6.6 and run it in 6.5.
  //
  // So we have to use reflection to inspect the return type.
  private static boolean shouldStoreCredentials(DefaultMavenArtifactRepository repo) {

    try {
      Method getConfiguredCredentials = DefaultMavenArtifactRepository.class
          .getMethod("getConfiguredCredentials");

      // This is for Gradle < 6.6. Once we no longer support versions of Gradle before 6.6
      if (getConfiguredCredentials.getReturnType().equals(Credentials.class)) {
        Credentials existingCredentials = (Credentials) getConfiguredCredentials.invoke(repo);
        return existingCredentials == null;
      } else if (getConfiguredCredentials.getReturnType().equals(Property.class)) {
        Property<?> existingCredentials = (Property<?>) getConfiguredCredentials.invoke(repo);
        return !existingCredentials.isPresent();
      } else {
        logger.warn("Error determining Gradle credentials API; expect authentication errors");
        return false;
      }
    } catch (ReflectiveOperationException e) {
      logger.warn("Error determining Gradle credentials API; expect authentication errors", e);
      return false;
    }
  }
}

