package com.modelgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleaseWorkflowTest {
  @Test
  void releaseWorkflowVerifiesPackagesSmokesAndPublishesArtifacts() throws Exception {
    Path workflow = Path.of(".github", "workflows", "release.yml");

    assertThat(workflow).exists();

    String yaml = Files.readString(workflow);
    assertThat(yaml).contains("workflow_dispatch:");
    assertThat(yaml).contains("release_tag:");
    assertThat(yaml).contains("required: true");
    assertThat(yaml).contains("tags:");
    assertThat(yaml).contains("v*");
    assertThat(yaml).contains("RELEASE_TAG: ${{ github.event_name == 'workflow_dispatch' && inputs.release_tag || github.ref_name }}");
    assertThat(yaml).contains("DOCKER_TAG: ${{ github.event_name == 'workflow_dispatch' && inputs.release_tag || github.ref_name }}");
    assertThat(yaml).contains("actions/setup-java@v4");
    assertThat(yaml).contains("java-version: '21'");
    assertThat(yaml).contains("mvn -q verify");
    assertThat(yaml).contains("mvn -q -DskipTests package");
    assertThat(yaml).contains("java -jar target/modelgate-0.1.jar --port 18788");
    assertThat(yaml).contains("curl --fail --silent http://localhost:18788/health");
    assertThat(yaml).contains("curl --fail --silent http://localhost:18788/ready");
    assertThat(yaml).contains("docker build -t modelgate:${DOCKER_TAG} .");
    assertThat(yaml).contains("target/modelgate-0.1.jar");
    assertThat(yaml).contains("softprops/action-gh-release@v2");
    assertThat(yaml).contains("tag_name: ${{ env.RELEASE_TAG }}");
  }
}
