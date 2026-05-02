package com.modelgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProviderReadinessMavenProfileTest {
  @Test
  void pomDefinesDedicatedProviderReadinessProfile() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));

    assertThat(pom).contains("<id>provider-readiness</id>");
    assertThat(pom).contains("<test>ProviderProductionReadinessGateTest</test>");
    assertThat(pom).contains("modelgate.provider.ready");
    assertThat(pom).contains(
        "mvn -q -Pprovider-readiness -Dmodelgate.provider.ready=openai test");
  }
}
