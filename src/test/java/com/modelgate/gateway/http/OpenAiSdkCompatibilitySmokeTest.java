package com.modelgate.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiSdkCompatibilitySmokeTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();
  private Javalin app;
  private HttpServer providerServer;

  @AfterEach
  void stopServers() {
    if (app != null) {
      app.stop();
    }
    if (providerServer != null) {
      providerServer.stop(0);
    }
  }

  @Test
  void acceptsOpenAiSdkStyleChatCompletionRequest() throws Exception {
    AtomicReference<String> providerPath = new AtomicReference<>();
    AtomicReference<String> providerAuthorization = new AtomicReference<>();
    AtomicReference<String> providerOrganization = new AtomicReference<>();
    AtomicReference<String> providerProject = new AtomicReference<>();
    AtomicReference<String> providerBody = new AtomicReference<>();
    startProviderServer(exchange -> {
      providerPath.set(exchange.getRequestURI().getPath());
      providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      providerOrganization.set(exchange.getRequestHeaders().getFirst("OpenAI-Organization"));
      providerProject.set(exchange.getRequestHeaders().getFirst("OpenAI-Project"));
      providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, """
          {
            "id": "chatcmpl-smoke",
            "object": "chat.completion",
            "choices": [
              {
                "index": 0,
                "message": {"role": "assistant", "content": "smoke ok"},
                "finish_reason": "stop"
              }
            ]
          }
          """);
    });
    app = GatewayApp.create().start(0);

    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("authorization", "Bearer sk-sdk-provider")
            .header("openai-organization", "org-smoke")
            .header("openai-project", "project-smoke")
            .header("x-modelgate-provider", "openai")
            .header("x-modelgate-custom-host", providerBaseUrl())
            .POST(HttpRequest.BodyPublishers.ofString("""
                {
                  "model": "gpt-4o-mini",
                  "messages": [{"role": "user", "content": "ping from sdk"}],
                  "max_tokens": 8
                }
                """))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(objectMapper.readTree(response.body()).path("id").asText()).isEqualTo("chatcmpl-smoke");
    assertThat(providerPath).hasValue("/v1/chat/completions");
    assertThat(providerAuthorization).hasValue("Bearer sk-sdk-provider");
    assertThat(providerOrganization).hasValue("org-smoke");
    assertThat(providerProject).hasValue("project-smoke");
    assertThat(providerBody.get()).contains("\"model\": \"gpt-4o-mini\"");
    assertThat(providerBody.get()).contains("\"content\": \"ping from sdk\"");
  }

  private void startProviderServer(ThrowingHandler handler) throws IOException {
    providerServer = HttpServer.create(new InetSocketAddress(0), 0);
    providerServer.createContext("/", exchange -> {
      try {
        handler.handle(exchange);
      } catch (Exception exception) {
        respond(exchange, 500, exception.getMessage());
      }
    });
    providerServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    providerServer.start();
  }

  private String providerBaseUrl() {
    return "http://localhost:" + providerServer.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().put("content-type", java.util.List.of("application/json"));
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @FunctionalInterface
  private interface ThrowingHandler {
    void handle(HttpExchange exchange) throws Exception;
  }
}
