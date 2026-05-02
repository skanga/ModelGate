package com.modelgate.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SocketRedisCacheStoreTest {
  @Test
  void sendsSetWithPxTtlAndReadsBulkStringValues() throws Exception {
    try (FakeRedisServer redis = new FakeRedisServer()) {
      SocketRedisCacheStore store = new SocketRedisCacheStore(
          URI.create("redis://localhost:" + redis.port() + "/2"),
          Duration.ofSeconds(2),
          Duration.ofSeconds(2));

      store.set("cache-key", "{\"ok\":true}", Duration.ofSeconds(3));
      Optional<String> value = store.get("cache-key");

      assertThat(value).contains("{\"ok\":true}");
      assertThat(redis.commands()).contains(
          List.of("SELECT", "2"),
          List.of("SET", "cache-key", "{\"ok\":true}", "PX", "3000"),
          List.of("GET", "cache-key"));
    }
  }

  private static final class FakeRedisServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<List<String>> commands = java.util.Collections.synchronizedList(new ArrayList<>());

    private FakeRedisServer() throws IOException {
      serverSocket = new ServerSocket(0);
      executor.submit(this::serve);
    }

    private int port() {
      return serverSocket.getLocalPort();
    }

    private List<List<String>> commands() {
      return List.copyOf(commands);
    }

    private void serve() {
      while (!serverSocket.isClosed()) {
        try {
          Socket socket = serverSocket.accept();
          executor.submit(() -> handle(socket));
        } catch (IOException ignored) {
          return;
        }
      }
    }

    private void handle(Socket socket) {
      try (socket;
          BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
        while (!socket.isClosed()) {
          List<String> command = readCommand(reader);
          if (command.isEmpty()) {
            return;
          }
          commands.add(command);
          switch (command.getFirst()) {
            case "GET" -> writer.write("$11\r\n{\"ok\":true}\r\n");
            default -> writer.write("+OK\r\n");
          }
          writer.flush();
        }
      } catch (IOException ignored) {
      }
    }

    private static List<String> readCommand(BufferedReader reader) throws IOException {
      String arrayHeader = reader.readLine();
      if (arrayHeader == null || !arrayHeader.startsWith("*")) {
        return List.of();
      }
      int count = Integer.parseInt(arrayHeader.substring(1));
      List<String> command = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String bulkHeader = reader.readLine();
        if (bulkHeader == null || !bulkHeader.startsWith("$")) {
          return List.of();
        }
        int length = Integer.parseInt(bulkHeader.substring(1));
        char[] value = new char[length];
        int read = reader.read(value, 0, length);
        if (read != length) {
          return List.of();
        }
        reader.readLine();
        command.add(new String(value));
      }
      return command;
    }

    @Override
    public void close() throws Exception {
      serverSocket.close();
      executor.close();
    }
  }
}
