package com.modelgate.gateway.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.gateway.headers.GatewayHeaders;
import com.modelgate.gateway.provider.ProviderCatalog;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RequestValidator {
  private static final String CONTENT_TYPE = "content-type";
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
  private static final Pattern DECIMAL_IPV4 = Pattern.compile("\\d{1,10}");
  private static final Pattern HEX_IPV4 = Pattern.compile("0x[0-9a-f]{1,8}", Pattern.CASE_INSENSITIVE);
  private static final Pattern WORKERS_AI_ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern AZURE_OPENAI_RESOURCE_NAME = Pattern.compile("[A-Za-z0-9-]+");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> BLOCKED_HOSTS = Set.of(
      "0.0.0.0",
      "169.254.169.254",
      "metadata.google.internal",
      "metadata",
      "metadata.azure.com",
      "instance-data");
  private static final List<String> BLOCKED_TLDS = List.of(
      ".local",
      ".localdomain",
      ".internal",
      ".intranet",
      ".lan",
      ".home",
      ".corp",
      ".test",
      ".invalid",
      ".onion",
      ".localhost");

  private final Set<String> allowedProviders;
  private final Set<String> trustedCustomHosts;

  public RequestValidator(Set<String> allowedProviders) {
    this(allowedProviders, Set.of());
  }

  public RequestValidator(Set<String> allowedProviders, Set<String> trustedCustomHosts) {
    this.allowedProviders = allowedProviders.stream()
        .map(provider -> provider.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    this.trustedCustomHosts = trustedCustomHosts == null ? Set.of() : trustedCustomHosts.stream()
        .map(RequestValidator::normalizeHost)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public ValidationResult validate(Map<String, String> headers) {
    HeaderLookup lookup = new HeaderLookup(headers);

    ValidationResult contentTypeResult = validateContentType(lookup.value(CONTENT_TYPE));
    if (!contentTypeResult.valid()) {
      return contentTypeResult;
    }

    String config = GatewayHeaders.value(headers, "config");
    String provider = GatewayHeaders.value(headers, "provider");
    if (isBlank(config) && isBlank(provider)) {
      return ValidationResult.badRequest(
          "Missing x-modelgate-config/x-modelgate-provider header (x-portkey compatibility aliases are accepted)");
    }

    if (!isBlank(provider) && !allowedProviders.contains(provider.trim().toLowerCase(Locale.ROOT))) {
      return ValidationResult.badRequest("Invalid provider: " + provider.trim());
    }

    ValidationResult defaultInputGuardrailsResult = validateDefaultGuardrailHeader(
        GatewayHeaders.value(headers, "default-input-guardrails"),
        "default input guardrails");
    if (!defaultInputGuardrailsResult.valid()) {
      return defaultInputGuardrailsResult;
    }
    ValidationResult defaultOutputGuardrailsResult = validateDefaultGuardrailHeader(
        GatewayHeaders.value(headers, "default-output-guardrails"),
        "default output guardrails");
    if (!defaultOutputGuardrailsResult.valid()) {
      return defaultOutputGuardrailsResult;
    }

    String customHost = GatewayHeaders.value(headers, "custom-host");
    ValidationResult configResult = validateConfig(config, provider, customHost);
    if (!configResult.valid()) {
      return configResult;
    }

    ValidationResult forwardHeadersResult = validateForwardHeaders(GatewayHeaders.value(headers, "forward-headers"));
    if (!forwardHeadersResult.valid()) {
      return forwardHeadersResult;
    }

    ValidationResult requestTimeoutResult = validateRequestTimeout(GatewayHeaders.value(headers, "request-timeout"));
    if (!requestTimeoutResult.valid()) {
      return requestTimeoutResult;
    }

    ValidationResult customHostResult = validateCustomHost(customHost);
    if (!customHostResult.valid()) {
      return customHostResult;
    }

    ValidationResult providerHostResult = validateProviderHost(
        provider,
        customHost,
        GatewayHeaders.value(headers, "workers-ai-account-id"),
        GatewayHeaders.value(headers, "azure-openai-resource-name"));
    if (!providerHostResult.valid()) {
      return providerHostResult;
    }

    return ValidationResult.ok();
  }

  private ValidationResult validateDefaultGuardrailHeader(String value, String label) {
    if (isBlank(value)) {
      return ValidationResult.ok();
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(value);
      if (!node.isArray()) {
        return ValidationResult.badRequest("Invalid " + label + ": expected a JSON array");
      }
      return ValidationResult.ok();
    } catch (JsonProcessingException exception) {
      return ValidationResult.badRequest("Invalid " + label + ": malformed JSON");
    }
  }

  private ValidationResult validateConfig(String config, String providerHeader, String inheritedCustomHost) {
    if (isBlank(config)) {
      return ValidationResult.ok();
    }

    JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(config);
    } catch (JsonProcessingException e) {
      return ValidationResult.badRequest("Invalid config passed");
    }
    JsonNode rootTargets = root.get("targets");
    boolean hasTargets = rootTargets != null && rootTargets.isArray() && !rootTargets.isEmpty();
    if (isBlank(providerHeader)
        && isBlank(textOrNull(root.get("provider")))
        && !hasTargets) {
      return ValidationResult.badRequest(
          "Either x-modelgate-provider needs to be passed or x-modelgate-config must contain provider details");
    }
    if (root.has("options")) {
      return ValidationResult.badRequest(
          "This version of config is not supported in this route. Please migrate to the latest version");
    }
    ValidationResult routingResult = validateRoutingShape(root);
    if (!routingResult.valid()) {
      return routingResult;
    }
    return validateConfigNode(root, inheritedCustomHost);
  }

  private static ValidationResult validateRoutingShape(JsonNode root) {
    JsonNode strategy = root.get("strategy");
    if (strategy == null || strategy.isNull() || strategy.isMissingNode()) {
      return ValidationResult.ok();
    }
    String mode = textOrNull(strategy.get("mode"));
    if (!"loadbalance".equalsIgnoreCase(mode)) {
      return ValidationResult.ok();
    }
    JsonNode targets = root.get("targets");
    if (targets == null || !targets.isArray() || targets.isEmpty()) {
      return ValidationResult.ok();
    }
    for (JsonNode target : targets) {
      JsonNode weightNode = target.get("weight");
      double weight = weightNode == null || weightNode.isNull() || weightNode.isMissingNode()
          ? 1
          : weightNode.asDouble();
      if (Double.isFinite(weight) && weight > 0) {
        return ValidationResult.ok();
      }
    }
    return ValidationResult.badRequest("Invalid loadbalance strategy: at least one target weight must be positive");
  }

  private ValidationResult validateConfigNode(JsonNode node, String inheritedCustomHost) {
    String provider = textOrNull(node.get("provider"));
    if (!isBlank(provider) && !allowedProviders.contains(provider.trim().toLowerCase(Locale.ROOT))) {
      return ValidationResult.badRequest("Invalid provider: " + provider.trim());
    }

    String customHost = textOrNull(field(node, "custom_host", "customHost"));
    ValidationResult customHostResult = validateCustomHost(customHost);
    if (!customHostResult.valid()) {
      return customHostResult;
    }

    String effectiveCustomHost = isBlank(customHost) ? inheritedCustomHost : customHost;
    ValidationResult providerHostResult = validateProviderHost(
        provider,
        effectiveCustomHost,
        textOrNull(field(node, "workers_ai_account_id", "workersAiAccountId")),
        textOrNull(field(node, "resource_name", "resourceName")));
    if (!providerHostResult.valid()) {
      return providerHostResult;
    }

    ValidationResult forwardHeadersResult = validateConfigForwardHeaders(field(node, "forward_headers", "forwardHeaders"));
    if (!forwardHeadersResult.valid()) {
      return forwardHeadersResult;
    }

    ValidationResult requestTimeoutResult = validateConfigRequestTimeout(field(node, "request_timeout", "requestTimeout"));
    if (!requestTimeoutResult.valid()) {
      return requestTimeoutResult;
    }

    JsonNode targets = node.get("targets");
    if (targets != null && targets.isArray()) {
      for (JsonNode target : targets) {
        if (isBlank(textOrNull(target.get("provider")))) {
          return ValidationResult.badRequest("Invalid target: provider is required");
        }
        ValidationResult targetResult = validateConfigNode(target, effectiveCustomHost);
        if (!targetResult.valid()) {
          return targetResult;
        }
      }
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateProviderHost(
      String provider,
      String customHost,
      String workersAiAccountId,
      String azureOpenAiResourceName) {
    if (isBlank(provider) || !isBlank(customHost)) {
      return ValidationResult.ok();
    }
    if ("workers-ai".equalsIgnoreCase(provider) && !isBlank(workersAiAccountId)) {
      return WORKERS_AI_ACCOUNT_ID.matcher(workersAiAccountId.trim()).matches()
          ? ValidationResult.ok()
          : ValidationResult.badRequest("Invalid workers_ai_account_id");
    }
    if ("azure-openai".equalsIgnoreCase(provider) && !isBlank(azureOpenAiResourceName)) {
      return AZURE_OPENAI_RESOURCE_NAME.matcher(azureOpenAiResourceName.trim()).matches()
          ? ValidationResult.ok()
          : ValidationResult.badRequest("Invalid resource_name");
    }
    if (ProviderCatalog.hasDefaultBaseUrl(provider)) {
      return ValidationResult.ok();
    }
    return ValidationResult.badRequest("Provider " + provider.trim() + " requires custom_host");
  }

  private static ValidationResult validateConfigForwardHeaders(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return ValidationResult.ok();
    }

    if (node.isArray()) {
      for (JsonNode header : node) {
        if (GatewayHeaders.isForwardHeadersHeader(header.asText())) {
          return invalidForwardHeaders();
        }
      }
      return ValidationResult.ok();
    }

    if (GatewayHeaders.isForwardHeadersHeader(node.asText())) {
      return invalidForwardHeaders();
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateContentType(String contentType) {
    if (isBlank(contentType)) {
      return ValidationResult.ok();
    }

    String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (mediaType.equals("application/json")
        || mediaType.endsWith("+json")
        || mediaType.equals("multipart/form-data")
        || mediaType.equals("application/octet-stream")
        || mediaType.startsWith("audio/")
        || mediaType.startsWith("image/")) {
      return ValidationResult.ok();
    }
    return ValidationResult.badRequest("Invalid content-type: " + contentType);
  }

  private static ValidationResult validateForwardHeaders(String forwardHeaders) {
    if (isBlank(forwardHeaders)) {
      return ValidationResult.ok();
    }

    for (String header : forwardHeaders.split(",")) {
      if (GatewayHeaders.isForwardHeadersHeader(header)) {
        return invalidForwardHeaders();
      }
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateRequestTimeout(String requestTimeout) {
    if (isBlank(requestTimeout)) {
      return ValidationResult.ok();
    }
    try {
      long parsed = Long.parseLong(requestTimeout.trim());
      return parsed > 0 ? ValidationResult.ok() : invalidRequestTimeout();
    } catch (NumberFormatException exception) {
      return invalidRequestTimeout();
    }
  }

  private static ValidationResult validateConfigRequestTimeout(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return ValidationResult.ok();
    }
    if (!node.canConvertToLong()) {
      return invalidRequestTimeout();
    }
    return node.asLong() > 0 ? ValidationResult.ok() : invalidRequestTimeout();
  }

  private ValidationResult validateCustomHost(String customHost) {
    if (isBlank(customHost)) {
      return ValidationResult.ok();
    }

    URI uri;
    try {
      uri = new URI(customHost.trim());
    } catch (URISyntaxException e) {
      return invalidCustomHost();
    }

    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (isBlank(scheme) || isBlank(host)) {
      return invalidCustomHost();
    }

    String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
    if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
      return invalidCustomHost();
    }

    String rawAuthority = uri.getRawAuthority();
    if (uri.getUserInfo() != null || rawAuthority.contains("@")) {
      return invalidCustomHost();
    }

    String normalizedHost = normalizeHost(host);
    if (hasAmbiguousIpv4LeadingZeros(rawHost(rawAuthority))) {
      return invalidCustomHost();
    }
    if (isTrustedLocalDevelopmentHost(normalizedHost)) {
      return ValidationResult.ok();
    }

    if (isBlockedMetadataHost(normalizedHost)) {
      return invalidCustomHost();
    }

    boolean blockedHost = isBlockedHostname(normalizedHost)
        || isBlockedIpLiteral(normalizedHost)
        || isAlternativeIpRepresentation(normalizedHost);
    if (!blockedHost) {
      return ValidationResult.ok();
    }

    return trustedCustomHosts.contains(normalizedHost) ? ValidationResult.ok() : invalidCustomHost();
  }

  private static String normalizeHost(String host) {
    String trimmed = host.trim();
    if (trimmed.endsWith(".")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
  }

  private static boolean isTrustedLocalDevelopmentHost(String host) {
    String ipLiteral = stripIpv6Brackets(host);
    return host.equals("localhost")
        || host.endsWith(".localhost")
        || host.equals("host.docker.internal")
        || host.equals("127.0.0.1")
        || ipLiteral.equals("::1");
  }

  private static boolean isBlockedHostname(String host) {
    return host.equals("api.portkey")
        || host.equals("api.portkey.ai")
        || host.endsWith(".api.portkey.ai")
        || isBlockedMetadataHost(host)
        || BLOCKED_HOSTS.contains(host)
        || BLOCKED_TLDS.stream().anyMatch(host::endsWith);
  }

  private static boolean isBlockedMetadataHost(String host) {
    return host.equals("169.254.169.254")
        || host.equals("metadata")
        || host.equals("metadata.google.internal")
        || host.endsWith(".metadata.google.internal")
        || host.equals("metadata.azure.com")
        || host.endsWith(".metadata.google.internal")
        || host.endsWith(".metadata.azure.com")
        || host.equals("instance-data");
  }

  private static boolean isBlockedIpLiteral(String host) {
    host = stripIpv6Brackets(host);
    if (host.contains(":")) {
      return isBlockedIpv6(host);
    }
    if (!IPV4.matcher(host).matches()) {
      return false;
    }
    return isBlockedIpv4(host);
  }

  private static String stripIpv6Brackets(String host) {
    if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }

  private static boolean isBlockedIpv4(String host) {
    String[] parts = host.split("\\.");
    int[] octets = new int[4];
    for (int i = 0; i < parts.length; i++) {
      if (parts[i].length() > 1 && parts[i].startsWith("0")) {
        return true;
      }
      try {
        octets[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException e) {
        return true;
      }
      if (octets[i] < 0 || octets[i] > 255) {
        return true;
      }
    }

    int first = octets[0];
    int second = octets[1];
    return first == 0
        || first == 10
        || first == 127
        || (first == 100 && second >= 64 && second <= 127)
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 0 && octets[2] == 0)
        || (first == 192 && second == 0 && octets[2] == 2)
        || (first == 192 && second == 168)
        || (first == 198 && (second == 18 || second == 19))
        || (first == 198 && second == 51 && octets[2] == 100)
        || (first == 203 && second == 0 && octets[2] == 113)
        || first >= 224;
  }

  private static String rawHost(String rawAuthority) {
    String rawHost = rawAuthority;
    int portSeparator = rawHost.lastIndexOf(':');
    if (portSeparator > -1 && rawHost.indexOf(']') < portSeparator) {
      rawHost = rawHost.substring(0, portSeparator);
    }
    return stripIpv6Brackets(rawHost).toLowerCase(Locale.ROOT);
  }

  private static boolean hasAmbiguousIpv4LeadingZeros(String host) {
    String[] parts = host.split("\\.");
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (!part.chars().allMatch(Character::isDigit)) {
        return false;
      }
      if (part.length() > 1 && part.startsWith("0")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAlternativeIpRepresentation(String host) {
    if (DECIMAL_IPV4.matcher(host).matches()) {
      try {
        long value = Long.parseLong(host);
        if (value >= 0 && value <= 0xffffffffL) {
          return true;
        }
      } catch (NumberFormatException exception) {
        return true;
      }
    }
    if (HEX_IPV4.matcher(host).matches()) {
      return true;
    }

    String[] parts = host.split("\\.");
    if (parts.length >= 2 && parts.length < 4) {
      for (String part : parts) {
        if (!part.chars().allMatch(Character::isDigit)) {
          return false;
        }
        try {
          if (Integer.parseInt(part) > 255) {
            return false;
          }
        } catch (NumberFormatException exception) {
          return true;
        }
      }
      return true;
    }
    return false;
  }

  private static boolean isBlockedIpv6(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    return normalized.equals("::")
        || normalized.equals("::1")
        || normalized.startsWith("fc")
        || normalized.startsWith("fd")
        || normalized.startsWith("fe8")
        || normalized.startsWith("fe9")
        || normalized.startsWith("fea")
        || normalized.startsWith("feb")
        || normalized.startsWith("ff");
  }

  private static ValidationResult invalidCustomHost() {
    return ValidationResult.badRequest("Invalid custom host");
  }

  private static ValidationResult invalidForwardHeaders() {
    return ValidationResult.badRequest("Invalid forward_headers: cannot forward gateway forward headers");
  }

  private static ValidationResult invalidRequestTimeout() {
    return ValidationResult.badRequest("Invalid request_timeout: must be a positive number of milliseconds");
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
  }

  private static JsonNode field(JsonNode node, String... fieldNames) {
    JsonNode selected = null;
    var fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      for (String fieldName : fieldNames) {
        if (entry.getKey().equals(fieldName)) {
          selected = entry.getValue();
          break;
        }
      }
    }
    return selected;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private record HeaderLookup(Map<String, String> headers) {
    String value(String name) {
      String direct = headers.get(name);
      if (direct != null) {
        return direct;
      }
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
      return null;
    }
  }
}
