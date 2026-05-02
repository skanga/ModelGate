package com.modelgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CiWorkflowTest {
  @Test
  void ciWorkflowVerifiesPackagesSmokeTestsJarAndBuildsDockerImage() throws Exception {
    Path workflow = Path.of(".github", "workflows", "ci.yml");

    assertThat(workflow).exists();
    String yaml = Files.readString(workflow);

    assertThat(yaml).contains("actions/setup-java@v4");
    assertThat(yaml).contains("java-version: '21'");
    assertThat(yaml).contains("mvn -q verify");
    assertThat(yaml).contains("mvn -q -DskipTests package");
    assertThat(yaml).contains("actions/upload-artifact@v4");
    assertThat(yaml).contains("target/modelgate-0.1.jar");
    assertThat(yaml).contains("java -jar target/modelgate-0.1.jar --port 18787");
    assertThat(yaml).contains("curl --fail --silent http://localhost:18787/health");
    assertThat(yaml).contains("curl --fail --silent http://localhost:18787/ready");
    assertThat(yaml).contains("docker build -t modelgate:ci .");
  }
}
