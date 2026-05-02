package com.modelgate.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

final class RequestLogBuffer {
  private static final int MAX_EVENTS = 100;
  private static final int LIVE_STREAM_BUFFER_BYTES = 1024 * 1024;

  private final ObjectMapper objectMapper;
  private final Deque<LogEvent> events = new ArrayDeque<>();
  private final Map<String, LiveSubscriber> subscribers = new LinkedHashMap<>();
  private long nextId;

  RequestLogBuffer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  synchronized void record(Map<String, Object> logPayload) {
    try {
      LogEvent event = new LogEvent(nextId++, objectMapper.writeValueAsString(logPayload));
      events.addLast(event);
      while (events.size() > MAX_EVENTS) {
        events.removeFirst();
      }
      broadcast(event);
    } catch (JsonProcessingException ignored) {
      // Logging must never change gateway request behavior.
    }
  }

  synchronized InputStream follow(String clientId) throws IOException {
    LiveSubscriber subscriber = new LiveSubscriber(clientId, this);
    subscribers.put(clientId, subscriber);
    subscriber.write("connected", null, clientId);
    return subscriber.input();
  }

  synchronized void follow(String clientId, OutputStream output) throws IOException {
    LiveSubscriber subscriber = new LiveSubscriber(clientId, this, output);
    subscribers.put(clientId, subscriber);
    subscriber.write("connected", null, clientId);
  }

  synchronized boolean hasSubscriber(String clientId) {
    return subscribers.containsKey(clientId);
  }

  synchronized void heartbeat(String clientId) {
    LiveSubscriber subscriber = subscribers.get(clientId);
    if (subscriber == null) {
      return;
    }
    try {
      subscriber.write("heartbeat", null, "pulse");
    } catch (IOException exception) {
      removeSubscriber(clientId);
    }
  }

  synchronized String renderSnapshot(String clientId) {
    StringBuilder sse = new StringBuilder();
    appendEvent(sse, "connected", null, clientId);
    for (LogEvent event : events) {
      appendEvent(sse, "log", event.id(), event.data());
    }
    return sse.toString();
  }

  private void broadcast(LogEvent event) {
    for (LiveSubscriber subscriber : subscribers.values().toArray(LiveSubscriber[]::new)) {
      try {
        subscriber.write("log", event.id(), event.data());
      } catch (IOException exception) {
        removeSubscriber(subscriber.clientId());
      }
    }
  }

  private synchronized void removeSubscriber(String clientId) {
    LiveSubscriber subscriber = subscribers.remove(clientId);
    if (subscriber != null) {
      subscriber.closeQuietly();
    }
  }

  private static void appendEvent(StringBuilder sse, String event, Long id, String data) {
    sse.append("event: ").append(event).append('\n');
    if (id != null) {
      sse.append("id: ").append(id).append('\n');
    }
    sse.append("data: ").append(data).append("\n\n");
  }

  private record LogEvent(long id, String data) {}

  private static final class LiveSubscriber {
    private final String clientId;
    private final PipedOutputStream output;
    private final InputStream input;
    private final OutputStream directOutput;

    private LiveSubscriber(String clientId, RequestLogBuffer owner) throws IOException {
      this.clientId = clientId;
      PipedInputStream pipedInput = new PipedInputStream(LIVE_STREAM_BUFFER_BYTES);
      this.output = new PipedOutputStream(pipedInput);
      this.directOutput = null;
      this.input = new InputStream() {
        @Override
        public int read() throws IOException {
          return pipedInput.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          return pipedInput.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
          try {
            pipedInput.close();
          } finally {
            owner.removeSubscriber(clientId);
          }
        }
      };
    }

    private LiveSubscriber(String clientId, RequestLogBuffer owner, OutputStream output) {
      this.clientId = clientId;
      this.output = null;
      this.input = InputStream.nullInputStream();
      this.directOutput = output;
    }

    private String clientId() {
      return clientId;
    }

    private InputStream input() {
      return input;
    }

    private void write(String event, Long id, String data) throws IOException {
      StringBuilder sse = new StringBuilder();
      appendEvent(sse, event, id, data);
      OutputStream target = directOutput == null ? output : directOutput;
      target.write(sse.toString().getBytes(StandardCharsets.UTF_8));
      target.flush();
    }

    private void closeQuietly() {
      try {
        if (output != null) {
          output.close();
        }
        if (directOutput != null) {
          directOutput.close();
        }
      } catch (IOException ignored) {
        // Ignore cleanup failures for abandoned SSE clients.
      }
    }
  }
}
