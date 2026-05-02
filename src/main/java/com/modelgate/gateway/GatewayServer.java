package com.modelgate.gateway;

import com.modelgate.gateway.config.GatewayRuntimeConfig;
import com.modelgate.gateway.http.GatewayApp;
import io.javalin.Javalin;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class GatewayServer {
  private static final int DEFAULT_PORT = 8787;

  private GatewayServer() {}

  public static void main(String[] args) {
    run(System.getenv(), args, (port, runtimeConfig) -> {
      Javalin app = GatewayApp.create(runtimeConfig).start(port);
      Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }, GatewayServer::awaitForever);
  }

  static void run(
      Map<String, String> environment,
      String[] args,
      ServerStarter serverStarter,
      ShutdownBlocker shutdownBlocker) {
    run(
        environment,
        args,
        (port, runtimeConfig) -> serverStarter.start(port),
        shutdownBlocker);
  }

  static void run(
      Map<String, String> environment,
      String[] args,
      RuntimeServerStarter serverStarter,
      ShutdownBlocker shutdownBlocker) {
    int port = resolvePort(environment, args);
    GatewayRuntimeConfig runtimeConfig = GatewayRuntimeConfig.fromEnvironment(environment);
    serverStarter.start(port, runtimeConfig);
    System.out.printf(Locale.ROOT, "ModelGate listening on http://localhost:%d/%n", port);
    try {
      shutdownBlocker.block();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  static int resolvePort(Map<String, String> environment, String[] args) {
    String configuredPort = environment.get("PORT");
    for (int index = 0; index < args.length; index++) {
      if ("--port".equals(args[index]) && index + 1 < args.length) {
        configuredPort = args[index + 1];
        index++;
      }
    }
    if (configuredPort == null || configuredPort.isBlank()) {
      return DEFAULT_PORT;
    }
    return parsePort(configuredPort);
  }

  private static int parsePort(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("Port must be between 1 and 65535: " + value);
      }
      return port;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Port must be between 1 and 65535: " + value, exception);
    }
  }

  private static void awaitForever() throws InterruptedException {
    new CountDownLatch(1).await();
  }

  @FunctionalInterface
  interface ServerStarter {
    void start(int port);
  }

  @FunctionalInterface
  interface RuntimeServerStarter {
    void start(int port, GatewayRuntimeConfig runtimeConfig);
  }

  @FunctionalInterface
  interface ShutdownBlocker {
    void block() throws InterruptedException;
  }
}
