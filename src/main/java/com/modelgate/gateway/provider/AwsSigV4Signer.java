package com.modelgate.gateway.provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AwsSigV4Signer {
  private static final DateTimeFormatter AMZ_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter SCOPE_DATE =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  private AwsSigV4Signer() {}

  static Map<String, String> sign(
      String method,
      String url,
      byte[] body,
      Map<String, String> headers,
      String service,
      String region,
      String accessKeyId,
      String secretAccessKey,
      String sessionToken) {
    return sign(method, url, body, headers, service, region, accessKeyId, secretAccessKey, sessionToken, Instant.now());
  }

  static Map<String, String> sign(
      String method,
      String url,
      byte[] body,
      Map<String, String> headers,
      String service,
      String region,
      String accessKeyId,
      String secretAccessKey,
      String sessionToken,
      Instant now) {
    URI uri = URI.create(url);
    byte[] safeBody = body == null ? new byte[0] : body.clone();
    String payloadHash = sha256Hex(safeBody);
    String amzDate = AMZ_DATE.format(now);
    String date = SCOPE_DATE.format(now);
    String signingRegion = hasText(region) ? region : "us-east-1";
    String signingService = hasText(service) ? service : "sagemaker";

    Map<String, String> signedHeaders = normalizeHeaders(headers);
    signedHeaders.put("host", host(uri));
    signedHeaders.put("x-amz-date", amzDate);
    signedHeaders.put("x-amz-content-sha256", payloadHash);
    if (hasText(sessionToken)) {
      signedHeaders.put("x-amz-security-token", sessionToken);
    }

    List<String> headerNames = signedHeaders.keySet().stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .distinct()
        .sorted()
        .toList();
    String canonicalHeaders = canonicalHeaders(signedHeaders, headerNames);
    String signedHeaderNames = String.join(";", headerNames);
    String canonicalRequest = (hasText(method) ? method : "POST").toUpperCase(Locale.ROOT)
        + "\n"
        + canonicalUri(uri)
        + "\n"
        + canonicalQuery(uri)
        + "\n"
        + canonicalHeaders
        + "\n"
        + signedHeaderNames
        + "\n"
        + payloadHash;
    String credentialScope = date + "/" + signingRegion + "/" + signingService + "/aws4_request";
    String stringToSign = "AWS4-HMAC-SHA256\n"
        + amzDate
        + "\n"
        + credentialScope
        + "\n"
        + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    byte[] signingKey = signingKey(secretAccessKey, date, signingRegion, signingService);
    String signature = hex(hmac(signingKey, stringToSign));
    signedHeaders.put(
        "authorization",
        "AWS4-HMAC-SHA256 Credential="
            + accessKeyId
            + "/"
            + credentialScope
            + ", SignedHeaders="
            + signedHeaderNames
            + ", Signature="
            + signature);
    return signedHeaders;
  }

  private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (headers == null) {
      return normalized;
    }
    headers.forEach((name, value) -> {
      if (hasText(name) && value != null && !"authorization".equalsIgnoreCase(name)) {
        normalized.put(name.toLowerCase(Locale.ROOT), value);
      }
    });
    return normalized;
  }

  private static String canonicalHeaders(Map<String, String> headers, List<String> names) {
    StringBuilder canonical = new StringBuilder();
    for (String name : names) {
      canonical.append(name).append(':').append(canonicalHeaderValue(headers.get(name))).append('\n');
    }
    return canonical.toString();
  }

  private static String canonicalHeaderValue(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private static String canonicalUri(URI uri) {
    String path = uri.getRawPath();
    return path == null || path.isBlank() ? "/" : path;
  }

  private static String canonicalQuery(URI uri) {
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return "";
    }
    String[] parts = query.split("&");
    List<String> encoded = new ArrayList<>();
    for (String part : parts) {
      int separator = part.indexOf('=');
      String name = separator >= 0 ? part.substring(0, separator) : part;
      String value = separator >= 0 ? part.substring(separator + 1) : "";
      encoded.add(uriEncode(name) + "=" + uriEncode(value));
    }
    encoded.sort(Comparator.naturalOrder());
    return String.join("&", encoded);
  }

  private static String host(URI uri) {
    int port = uri.getPort();
    if (port < 0 || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
        || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()))) {
      return uri.getHost();
    }
    return uri.getHost() + ":" + port;
  }

  private static String uriEncode(String value) {
    StringBuilder encoded = new StringBuilder();
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      int c = b & 0xff;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.' || c == '~') {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return encoded.toString();
  }

  private static byte[] signingKey(String secretAccessKey, String date, String region, String service) {
    byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
    byte[] dateRegionKey = hmac(dateKey, region);
    byte[] dateRegionServiceKey = hmac(dateRegionKey, service);
    return hmac(dateRegionServiceKey, "aws4_request");
  }

  private static String sha256Hex(byte[] value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return hex(digest.digest(value));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to hash AWS SigV4 payload", exception);
    }
  }

  private static byte[] hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign AWS SigV4 request", exception);
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16));
      hex.append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
