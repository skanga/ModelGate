package com.modelgate.gateway.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SocketRedisCacheStore implements RedisCacheStore {
  private static final int DEFAULT_REDIS_PORT = 6379;

  private final URI redisUrl;
  private final Duration connectTimeout;
  private final Duration commandTimeout;
  private final String username;
  private final String password;
  private final int database;

  public SocketRedisCacheStore(URI redisUrl, Duration connectTimeout, Duration commandTimeout) {
    this.redisUrl = redisUrl;
    this.connectTimeout = validTimeout(connectTimeout);
    this.commandTimeout = validTimeout(commandTimeout);
    String userInfo = redisUrl == null ? null : redisUrl.getUserInfo();
    String[] credentials = userInfo == null ? new String[0] : userInfo.split(":", 2);
    this.username = credentials.length == 2 && !credentials[0].isBlank() ? credentials[0] : null;
    this.password = credentials.length == 1 ? credentials[0] : (credentials.length == 2 ? credentials[1] : null);
    this.database = parseDatabase(redisUrl);
  }

  @Override
  public Optional<String> get(String key) {
    try (RedisConnection connection = connect()) {
      Object response = connection.command("GET", key);
      return response instanceof String value ? Optional.of(value) : Optional.empty();
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void set(String key, String body, Duration ttl) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      return;
    }
    try (RedisConnection connection = connect()) {
      connection.command("SET", key, body, "PX", String.valueOf(ttl.toMillis()));
    } catch (IOException | RuntimeException ignored) {
      // Cache writes must not fail provider traffic.
    }
  }

  private RedisConnection connect() throws IOException {
    Socket socket = new Socket();
    socket.connect(
        new InetSocketAddress(redisUrl.getHost(), redisUrl.getPort() > 0 ? redisUrl.getPort() : DEFAULT_REDIS_PORT),
        Math.toIntExact(connectTimeout.toMillis()));
    socket.setSoTimeout(Math.toIntExact(commandTimeout.toMillis()));
    RedisConnection connection = new RedisConnection(socket);
    if (password != null && !password.isBlank()) {
      if (username == null) {
        connection.command("AUTH", password);
      } else {
        connection.command("AUTH", username, password);
      }
    }
    if (database > 0) {
      connection.command("SELECT", String.valueOf(database));
    }
    return connection;
  }

  private static int parseDatabase(URI redisUrl) {
    String path = redisUrl == null ? null : redisUrl.getPath();
    if (path == null || path.isBlank() || "/".equals(path)) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(path.substring(1)));
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private static Duration validTimeout(Duration timeout) {
    return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(2) : timeout;
  }

  private static final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final BufferedInputStream input;
    private final BufferedOutputStream output;

    private RedisConnection(Socket socket) throws IOException {
      this.socket = socket;
      this.input = new BufferedInputStream(socket.getInputStream());
      this.output = new BufferedOutputStream(socket.getOutputStream());
    }

    private Object command(String... parts) throws IOException {
      writeCommand(parts);
      return readResponse();
    }

    private void writeCommand(String... parts) throws IOException {
      output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
      for (String part : parts) {
        byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
        output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
      }
      output.flush();
    }

    private Object readResponse() throws IOException {
      int prefix = input.read();
      return switch (prefix) {
        case '+' -> readLine();
        case '-' -> throw new IOException(readLine());
        case ':' -> Long.parseLong(readLine());
        case '$' -> readBulkString();
        case '*' -> readArray();
        case -1 -> throw new IOException("Redis connection closed");
        default -> throw new IOException("Unexpected Redis response prefix: " + (char) prefix);
      };
    }

    private String readBulkString() throws IOException {
      int length = Integer.parseInt(readLine());
      if (length < 0) {
        return null;
      }
      byte[] bytes = input.readNBytes(length);
      if (bytes.length != length) {
        throw new IOException("Incomplete Redis bulk string");
      }
      expectCrLf();
      return new String(bytes, StandardCharsets.UTF_8);
    }

    private List<Object> readArray() throws IOException {
      int count = Integer.parseInt(readLine());
      List<Object> values = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        values.add(readResponse());
      }
      return values;
    }

    private String readLine() throws IOException {
      StringBuilder out = new StringBuilder();
      while (true) {
        int value = input.read();
        if (value == -1) {
          throw new IOException("Redis connection closed");
        }
        if (value == '\r') {
          int newline = input.read();
          if (newline != '\n') {
            throw new IOException("Malformed Redis line ending");
          }
          return out.toString();
        }
        out.append((char) value);
      }
    }

    private void expectCrLf() throws IOException {
      int carriageReturn = input.read();
      int newline = input.read();
      if (carriageReturn != '\r' || newline != '\n') {
        throw new IOException("Malformed Redis bulk string ending");
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }
}
