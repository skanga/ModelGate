package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class ProviderGatewayContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();

  @TestFactory
  Iterable<DynamicTest> gatewayProviderContractsMatchMockedUpstreams() throws Exception {
    JsonNode root = readContracts();
    assertThat(root.path("contracts")).isNotEmpty();

    return StreamSupport.stream(root.path("contracts").spliterator(), false)
        .map(contract -> DynamicTest.dynamicTest(
            contract.path("name").asText("provider gateway contract"),
            () -> runContract(contract)))
        .toList();
  }

  private JsonNode readContracts() throws Exception {
    try (InputStream stream = getClass().getResourceAsStream("/provider-contracts/gateway-contracts.json")) {
      assertThat(stream).as("gateway provider contract fixture file").isNotNull();
      return objectMapper.readTree(stream);
    }
  }

  private void runContract(JsonNode contract) throws Exception {
    CapturedProviderRequest captured = new CapturedProviderRequest();
    HttpServer providerServer = startProviderServer(contract, captured);
    Javalin app = null;
    try {
      app = GatewayApp.create().start(0);

      String method = contract.path("method").asText("POST");
      HttpResponse<byte[]> response = client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + contract.path("endpoint").asText()))
              .header("content-type", contract.path("content_type").asText("application/json"))
              .header("authorization", "Bearer " + contract.path("api_key").asText("sk-contract"))
              .header("x-modelgate-config", gatewayConfig(contract, providerServer))
              .method(method, requestBodyPublisher(contract, method))
              .build(),
          HttpResponse.BodyHandlers.ofByteArray());
      String responseText = new String(response.body(), StandardCharsets.UTF_8);

      assertThat(response.statusCode()).as(contract.path("name").asText()).isEqualTo(contract.path("expected_status").asInt(200));
      assertThat(captured.method.get()).as(contract.path("name").asText())
          .isEqualTo(contract.path("expected_provider_method").asText("POST"));
      assertThat(captured.path.get()).as(contract.path("name").asText()).isEqualTo(contract.path("expected_provider_path").asText());
      for (JsonNode assertion : contract.path("provider_body_contains")) {
        assertThat(captured.body.get()).as(contract.path("name").asText()).contains(assertion.asText());
      }
      for (JsonNode assertion : contract.path("provider_body_not_contains")) {
        assertThat(captured.body.get()).as(contract.path("name").asText()).doesNotContain(assertion.asText());
      }
      for (JsonNode assertion : contract.path("response_body_contains")) {
        assertThat(responseText).as(contract.path("name").asText()).contains(assertion.asText());
      }
      for (JsonNode assertion : contract.path("response_body_not_contains")) {
        assertThat(responseText).as(contract.path("name").asText()).doesNotContain(assertion.asText());
      }
      if (contract.path("expected_provider_content_type").isTextual()) {
        assertThat(captured.headers.get()).as(contract.path("name").asText())
            .containsEntry("content-type", contract.path("expected_provider_content_type").asText());
      }
      if (contract.path("expected_provider_body_base64").isTextual()) {
        assertThat(captured.bodyBytes.get()).as(contract.path("name").asText())
            .isEqualTo(Base64.getDecoder().decode(contract.path("expected_provider_body_base64").asText()));
      }
      if (contract.path("response_body_base64").isTextual()) {
        assertThat(response.body()).as(contract.path("name").asText())
            .isEqualTo(Base64.getDecoder().decode(contract.path("response_body_base64").asText()));
      }
      for (JsonNode header : contract.path("expected_provider_headers")) {
        assertThat(captured.headers.get()).as(contract.path("name").asText())
            .containsEntry(header.path("name").asText().toLowerCase(), header.path("value").asText());
      }
      for (JsonNode header : contract.path("expected_provider_header_prefixes")) {
        assertThat(captured.headers.get().get(header.path("name").asText().toLowerCase()))
            .as(contract.path("name").asText())
            .startsWith(header.path("value").asText());
      }
      for (JsonNode header : contract.path("expected_provider_header_contains")) {
        assertThat(captured.headers.get().get(header.path("name").asText().toLowerCase()))
            .as(contract.path("name").asText())
            .contains(header.path("value").asText());
      }
      for (JsonNode header : contract.path("expected_provider_header_not_contains")) {
        assertThat(captured.headers.get().get(header.path("name").asText().toLowerCase()))
            .as(contract.path("name").asText())
            .doesNotContain(header.path("value").asText());
      }
      for (JsonNode header : contract.path("forbidden_provider_headers")) {
        assertThat(captured.headers.get()).as(contract.path("name").asText())
            .doesNotContainKey(header.asText().toLowerCase());
      }
      for (JsonNode header : contract.path("expected_response_headers")) {
        assertThat(response.headers().firstValue(header.path("name").asText()).orElse(""))
            .as(contract.path("name").asText())
            .contains(header.path("value").asText());
      }
    } finally {
      if (app != null) {
        app.stop();
      }
      providerServer.stop(0);
    }
  }

  private static HttpRequest.BodyPublisher requestBodyPublisher(JsonNode contract, String method) {
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return HttpRequest.BodyPublishers.noBody();
    }
    if (contract.path("request_body_base64").isTextual()) {
      return HttpRequest.BodyPublishers.ofByteArray(Base64.getDecoder().decode(contract.path("request_body_base64").asText()));
    }
    if (contract.path("request_body_text").isTextual()) {
      return HttpRequest.BodyPublishers.ofString(contract.path("request_body_text").asText());
    }
    return HttpRequest.BodyPublishers.ofString(contract.path("request_body").toString());
  }

  private String gatewayConfig(JsonNode contract, HttpServer providerServer) throws Exception {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("provider", contract.path("provider").asText());
    config.put("api_key", contract.path("api_key").asText("sk-contract"));
    config.put("custom_host",
        "http://localhost:" + providerServer.getAddress().getPort() + contract.path("custom_host_path").asText(""));
    JsonNode options = contract.path("config");
    if (options.isObject()) {
      options.fields().forEachRemaining(entry -> config.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class)));
    }
    return objectMapper.writeValueAsString(config);
  }

  private HttpServer startProviderServer(JsonNode contract, CapturedProviderRequest captured) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", exchange -> {
      try {
        captured.capture(exchange);
        for (JsonNode header : contract.path("provider_response_headers")) {
          exchange.getResponseHeaders().put(header.path("name").asText(), List.of(header.path("value").asText()));
        }
        respond(exchange, contract.path("provider_status").asInt(200), providerResponseBody(contract),
            contract.path("provider_response_content_type").asText("application/json"));
      } catch (Exception exception) {
        respond(exchange, 500, objectMapper.writeValueAsString(Map.of("error", exception.getMessage())), "application/json");
      }
    });
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    return server;
  }

  private byte[] providerResponseBody(JsonNode contract) {
    JsonNode responseBase64 = contract.path("provider_response_base64");
    if (responseBase64.isTextual()) {
      return Base64.getDecoder().decode(responseBase64.asText());
    }
    JsonNode responseText = contract.path("provider_response_text");
    if (responseText.isTextual()) {
      return responseText.asText().getBytes(StandardCharsets.UTF_8);
    }
    return contract.path("provider_response_body").toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
    respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
  }

  private static void respond(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
    exchange.getResponseHeaders().put("content-type", List.of(contentType));
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static final class CapturedProviderRequest {
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<byte[]> bodyBytes = new AtomicReference<>(new byte[0]);
    private final AtomicReference<Map<String, String>> headers = new AtomicReference<>(Map.of());

    private void capture(HttpExchange exchange) throws IOException {
      method.set(exchange.getRequestMethod());
      String rawPath = exchange.getRequestURI().getRawPath();
      String rawQuery = exchange.getRequestURI().getRawQuery();
      path.set(rawQuery == null ? rawPath : rawPath + "?" + rawQuery);
      byte[] bytes = exchange.getRequestBody().readAllBytes();
      bodyBytes.set(Arrays.copyOf(bytes, bytes.length));
      body.set(new String(bytes, StandardCharsets.UTF_8));
      Map<String, String> capturedHeaders = new LinkedHashMap<>();
      exchange.getRequestHeaders().forEach((key, values) -> {
        if (!values.isEmpty()) {
          capturedHeaders.put(key.toLowerCase(), values.getFirst());
        }
      });
      headers.set(capturedHeaders);
    }
  }
}
