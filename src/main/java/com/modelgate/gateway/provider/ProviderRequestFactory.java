package com.modelgate.gateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelgate.gateway.config.GatewayConfig;
import com.modelgate.gateway.config.GatewayConfigParser;
import com.modelgate.gateway.config.ProviderOptions;
import com.modelgate.gateway.config.RetrySettings;
import com.modelgate.gateway.config.Target;
import com.modelgate.gateway.headers.GatewayHeaders;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ProviderRequestFactory {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
  private static final List<Integer> DEFAULT_RETRY_STATUS_CODES = List.of(429, 500, 502, 503, 504);
  private static final Set<String> PROVIDERS_WITH_OPENAI_CLIENT_BASE_URL = Set.of(
      "gemini",
      "groq",
      "cerebras",
      "inception",
      "sambanova");
  private static final Set<String> BEDROCK_INVOKE_MODELS = Set.of(
      "cohere.command-light-text-v14",
      "cohere.command-text-v14",
      "ai21.j2-mid-v1",
      "ai21.j2-ultra-v1");
  private static final Set<String> AZURE_AI_NON_INFERENCE_PATHS = Set.of(
      "/v1/batches",
      "/v1/files",
      "/v1/fine_tuning/jobs");
  private static final Set<String> AZURE_OPENAI_ROOT_PATHS = Set.of(
      "/v1/batches",
      "/v1/files",
      "/v1/fine_tuning/jobs",
      "/v1/models",
      "/v1/responses");
  private static final Pattern WORKERS_AI_ACCOUNT_ID = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern AZURE_OPENAI_RESOURCE_NAME = Pattern.compile("[A-Za-z0-9-]+");
  private static final String STABILITY_MULTIPART_BOUNDARY = "modelgate-stability-boundary";
  private static final Set<String> ANTHROPIC_MESSAGES_FIELDS = Set.of(
      "model",
      "messages",
      "max_tokens",
      "container",
      "mcp_servers",
      "metadata",
      "service_tier",
      "stop_sequences",
      "stream",
      "system",
      "temperature",
      "thinking",
      "tool_choice",
      "tools",
      "top_k",
      "top_p");
  private static final AnthropicMessageTransformer ANTHROPIC_TRANSFORMER =
      new AnthropicMessageTransformer(new ObjectMapper());
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient TOKEN_HTTP_CLIENT = HttpClient.newHttpClient();
  private static final ConcurrentHashMap<String, CachedToken> TOKEN_CACHE = new ConcurrentHashMap<>();

  private final GatewayConfigParser parser;

  public ProviderRequestFactory(GatewayConfigParser parser) {
    this.parser = parser;
  }

  public ProviderRequest forEndpoint(String endpointPath, String body, Map<String, String> headers) {
    return forEndpoint(endpointPath, "POST", body, headers);
  }

  public ProviderRequest forEndpoint(String endpointPath, String method, String body, Map<String, String> headers) {
    return forEndpoint(endpointPath, method, body, headers, false);
  }

  public ProviderRequest forEndpoint(
      String endpointPath, String method, String body, Map<String, String> headers, boolean proxyHeaders) {
    Map<String, String> normalizedHeaders = normalizeHeaders(headers);
    GatewayConfig config = parser.parse(
        GatewayHeaders.valueOrDefault(normalizedHeaders, "config", "{}"),
        normalizedHeaders);
    String customHost = GatewayHeaders.value(normalizedHeaders, "custom-host");
    String baseUrl = baseUrl(config.provider(), endpointPath, customHost, config.customHost(), config.providerOptions());
    String providerMethod = providerMethod(config.provider(), endpointPath, method);
    String providerEndpointPath = providerEndpointPath(
        config.provider(), endpointPath, providerMethod, baseUrl, body, config.providerOptions());
    String providerBody = providerBody(config.provider(), providerEndpointPath, body, config.providerOptions());
    String providerUrl = joinUrl(baseUrl, providerEndpointPath);
    Map<String, String> providerHeaders = proxyHeaders
        ? proxyHeaders(config, normalizedHeaders, providerMethod)
        : providerHeaders(config, normalizedHeaders, providerMethod, providerEndpointPath, body);
    if (!proxyHeaders) {
      providerHeaders = signSagemakerIfConfigured(
          config.provider(), config.providerOptions(), providerUrl, providerMethod, providerBody.getBytes(StandardCharsets.UTF_8), providerHeaders);
    }

    return new ProviderRequest(
        providerUrl,
        providerMethod,
        providerHeaders,
        providerBody,
        retryPolicy(config.retry()),
        timeout(config, normalizedHeaders));
  }

  public ProviderRequest forEndpointRawBody(
      String endpointPath,
      String method,
      byte[] bodyBytes,
      Map<String, String> headers,
      boolean proxyHeaders) {
    Map<String, String> normalizedHeaders = normalizeHeaders(headers);
    GatewayConfig config = parser.parse(
        GatewayHeaders.valueOrDefault(normalizedHeaders, "config", "{}"),
        normalizedHeaders);
    String customHost = GatewayHeaders.value(normalizedHeaders, "custom-host");
    String baseUrl = baseUrl(config.provider(), endpointPath, customHost, config.customHost(), config.providerOptions());
    String providerMethod = providerMethod(config.provider(), endpointPath, method);
    byte[] safeBodyBytes = bodyBytes == null ? new byte[0] : Arrays.copyOf(bodyBytes, bodyBytes.length);
    String routingBody = routingBodyForRawBody(normalizedHeaders, safeBodyBytes);
    String providerEndpointPath = providerEndpointPath(
        config.provider(), endpointPath, providerMethod, baseUrl, routingBody, config.providerOptions());
    String providerUrl = joinUrl(baseUrl, providerEndpointPath);
    Map<String, String> providerHeaders = proxyHeaders
        ? proxyHeaders(config, normalizedHeaders, providerMethod)
        : providerHeaders(config, normalizedHeaders, providerMethod, providerEndpointPath, routingBody);
    if (!proxyHeaders) {
      providerHeaders = signSagemakerIfConfigured(
          config.provider(), config.providerOptions(), providerUrl, providerMethod, safeBodyBytes, providerHeaders);
    }

    return new ProviderRequest(
        providerUrl,
        providerMethod,
        providerHeaders,
        new String(safeBodyBytes, StandardCharsets.UTF_8),
        safeBodyBytes,
        retryPolicy(config.retry()),
        timeout(config, normalizedHeaders));
  }

  public ProviderRequest forTarget(
      Target target, String endpointPath, String body, Map<String, String> headers) {
    return forTarget(target, endpointPath, "POST", body, headers);
  }

  public ProviderRequest forTarget(
      Target target, String endpointPath, String method, String body, Map<String, String> headers) {
    return forTarget(target, endpointPath, method, body, headers, false);
  }

  public ProviderRequest forTarget(
      Target target,
      String endpointPath,
      String method,
      String body,
      Map<String, String> headers,
      boolean proxyHeaders) {
    return forTarget(target, endpointPath, method, body, headers, proxyHeaders, null);
  }

  public ProviderRequest forTarget(
      Target target,
      String endpointPath,
      String method,
      String body,
      Map<String, String> headers,
      boolean proxyHeaders,
      GatewayConfig inheritedConfig) {
    Map<String, String> normalizedHeaders = normalizeHeaders(headers);
    ProviderOptions providerOptions = effectiveProviderOptions(target, inheritedConfig);
    String baseUrl = hasText(target.customHost())
        ? target.customHost()
        : (inheritedConfig != null && hasText(inheritedConfig.customHost())
            ? inheritedConfig.customHost()
            : baseUrl(target.provider(), endpointPath, null, null, providerOptions));
    String providerMethod = providerMethod(target.provider(), endpointPath, method);
    String providerEndpointPath = providerEndpointPath(target.provider(), endpointPath, providerMethod, baseUrl, body, providerOptions);
    String providerBody = providerBody(target.provider(), providerEndpointPath, body, providerOptions);
    String providerUrl = joinUrl(baseUrl, providerEndpointPath);
    Map<String, String> providerHeaders = proxyHeaders
        ? proxyHeaders(target, inheritedConfig, normalizedHeaders, providerMethod)
        : providerHeaders(target, inheritedConfig, normalizedHeaders, providerMethod, providerEndpointPath, body);
    if (!proxyHeaders) {
      providerHeaders = signSagemakerIfConfigured(
          target.provider(), providerOptions, providerUrl, providerMethod, providerBody.getBytes(StandardCharsets.UTF_8), providerHeaders);
    }

    return new ProviderRequest(
        providerUrl,
        providerMethod,
        providerHeaders,
        providerBody,
        retryPolicy(target, inheritedConfig),
        timeout(target, inheritedConfig, normalizedHeaders));
  }

  public ProviderRequest forTargetRawBody(
      Target target,
      String endpointPath,
      String method,
      byte[] bodyBytes,
      Map<String, String> headers,
      boolean proxyHeaders,
      GatewayConfig inheritedConfig) {
    Map<String, String> normalizedHeaders = normalizeHeaders(headers);
    ProviderOptions providerOptions = effectiveProviderOptions(target, inheritedConfig);
    String baseUrl = hasText(target.customHost())
        ? target.customHost()
        : (inheritedConfig != null && hasText(inheritedConfig.customHost())
            ? inheritedConfig.customHost()
            : baseUrl(target.provider(), endpointPath, null, null, providerOptions));
    String providerMethod = providerMethod(target.provider(), endpointPath, method);
    byte[] safeBodyBytes = bodyBytes == null ? new byte[0] : Arrays.copyOf(bodyBytes, bodyBytes.length);
    String routingBody = routingBodyForRawBody(normalizedHeaders, safeBodyBytes);
    String providerEndpointPath = providerEndpointPath(target.provider(), endpointPath, providerMethod, baseUrl, routingBody, providerOptions);
    String providerUrl = joinUrl(baseUrl, providerEndpointPath);
    Map<String, String> providerHeaders = proxyHeaders
        ? proxyHeaders(target, inheritedConfig, normalizedHeaders, providerMethod)
        : providerHeaders(target, inheritedConfig, normalizedHeaders, providerMethod, providerEndpointPath, routingBody);
    if (!proxyHeaders) {
      providerHeaders = signSagemakerIfConfigured(
          target.provider(), providerOptions, providerUrl, providerMethod, safeBodyBytes, providerHeaders);
    }

    return new ProviderRequest(
        providerUrl,
        providerMethod,
        providerHeaders,
        new String(safeBodyBytes, StandardCharsets.UTF_8),
        safeBodyBytes,
        retryPolicy(target, inheritedConfig),
        timeout(target, inheritedConfig, normalizedHeaders));
  }

  private static Map<String, String> providerHeaders(
      GatewayConfig config, Map<String, String> headers, String method) {
    return providerHeaders(config, headers, method, null);
  }

  private static Map<String, String> providerHeaders(
      GatewayConfig config, Map<String, String> headers, String method, String providerEndpointPath) {
    return providerHeaders(config, headers, method, providerEndpointPath, "");
  }

  private static Map<String, String> providerHeaders(
      GatewayConfig config, Map<String, String> headers, String method, String providerEndpointPath, String requestBody) {
    Map<String, String> providerHeaders = new LinkedHashMap<>();
    if (shouldForwardContentType(method)) {
      providerHeaders.put("content-type", headers.getOrDefault("content-type", "application/json"));
    }
    addProviderCredentialHeaders(
        providerHeaders,
        config.provider(),
        config.apiKey(),
        config.providerOptions(),
        headers);
    addProviderOptionHeaders(providerHeaders, config.provider(), config.providerOptions(), providerEndpointPath, requestBody);
    addExplicitForwardHeaders(providerHeaders, headers, config.forwardHeaders());
    return providerHeaders;
  }

  private static Map<String, String> providerHeaders(
      Target target, GatewayConfig inheritedConfig, Map<String, String> headers, String method) {
    return providerHeaders(target, inheritedConfig, headers, method, null);
  }

  private static Map<String, String> providerHeaders(
      Target target,
      GatewayConfig inheritedConfig,
      Map<String, String> headers,
      String method,
      String providerEndpointPath) {
    return providerHeaders(target, inheritedConfig, headers, method, providerEndpointPath, "");
  }

  private static Map<String, String> providerHeaders(
      Target target,
      GatewayConfig inheritedConfig,
      Map<String, String> headers,
      String method,
      String providerEndpointPath,
      String requestBody) {
    Map<String, String> providerHeaders = new LinkedHashMap<>();
    if (shouldForwardContentType(method)) {
      providerHeaders.put("content-type", headers.getOrDefault("content-type", "application/json"));
    }
    ProviderOptions providerOptions = effectiveProviderOptions(target, inheritedConfig);
    addProviderCredentialHeaders(
        providerHeaders,
        target.provider(),
        effectiveApiKey(target, inheritedConfig),
        providerOptions,
        headers);
    addProviderOptionHeaders(providerHeaders, target.provider(), providerOptions, providerEndpointPath, requestBody);
    addExplicitForwardHeaders(providerHeaders, headers, forwardHeaders(target, inheritedConfig));
    return providerHeaders;
  }

  private static void addProviderCredentialHeaders(
      Map<String, String> providerHeaders,
      String provider,
      String apiKey,
      ProviderOptions providerOptions,
      Map<String, String> requestHeaders) {
    if ("anthropic".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey)
          ? apiKey
          : firstText(
              providerOptions.stringValue("apiKey"),
              firstText(providerOptions.stringValue("anthropicApiKey"), requestHeaders.get("x-api-key")));
      putIfHasText(providerHeaders, "x-api-key", effectiveApiKey);
      return;
    }

    if ("azure-openai".equalsIgnoreCase(provider)) {
      String azureAdToken = providerOptions.stringValue("azureAdToken");
      if (hasText(azureAdToken)) {
        providerHeaders.put("authorization", bearerValue(azureAdToken));
        return;
      }
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      if (hasText(effectiveApiKey)) {
        providerHeaders.put("api-key", effectiveApiKey);
      }
      return;
    }

    if ("azure-ai".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      if (isAzureAiAnthropicRequest(providerOptions)) {
        putIfHasText(providerHeaders, "x-api-key", effectiveApiKey);
        return;
      }
      String azureAdToken = providerOptions.stringValue("azureAdToken");
      if (hasText(azureAdToken)) {
        providerHeaders.put("authorization", bearerValue(azureAdToken));
        return;
      }
      String azureOAuthToken = azureAiAccessToken(providerOptions);
      if (hasText(azureOAuthToken)) {
        providerHeaders.put("authorization", bearerValue(azureOAuthToken));
        return;
      }
      if (hasText(effectiveApiKey)) {
        providerHeaders.put("authorization", "Bearer " + effectiveApiKey);
      }
      return;
    }

    if ("vertex-ai".equalsIgnoreCase(provider)) {
      String vertexAccessToken = vertexServiceAccountAccessToken(providerOptions);
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      String token = firstText(vertexAccessToken, effectiveApiKey);
      if (hasText(token)) {
        providerHeaders.put("authorization", "Bearer " + token);
      }
      return;
    }

    if ("google".equalsIgnoreCase(provider)) {
      return;
    }

    if ("palm".equalsIgnoreCase(provider)) {
      return;
    }

    if ("triton".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider)) {
      return;
    }

    if ("reka-ai".equalsIgnoreCase(provider) || "segmind".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      putIfHasText(providerHeaders, "x-api-key", effectiveApiKey);
      return;
    }

    if ("qdrant".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      if (hasText(effectiveApiKey)) {
        providerHeaders.put("api-key", "Bearer " + effectiveApiKey);
      }
      return;
    }

    if ("bytez".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      if (hasText(effectiveApiKey)) {
        providerHeaders.put("authorization", "Key " + effectiveApiKey);
      }
      providerHeaders.put("user-agent", "modelgate");
      return;
    }

    if ("modal".equalsIgnoreCase(provider)) {
      String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
      if (hasText(effectiveApiKey)) {
        providerHeaders.put("authorization", "Bearer " + effectiveApiKey);
        return;
      }
      putIfHasText(providerHeaders, "model-key", firstText(
          providerOptionValue(providerOptions, "modelKey", "model-key"),
          requestHeaders.get("model-key")));
      putIfHasText(providerHeaders, "model-secret", firstText(
          providerOptionValue(providerOptions, "modelSecret", "model-secret"),
          requestHeaders.get("model-secret")));
      return;
    }

    String effectiveApiKey = hasText(apiKey) ? apiKey : providerOptions.stringValue("apiKey");
    if (hasText(effectiveApiKey)) {
      providerHeaders.put("authorization", "Bearer " + effectiveApiKey);
      return;
    }

    String authorization = requestHeaders.get("authorization");
    if (authorization != null) {
      providerHeaders.put("authorization", authorization);
    }
  }

  private static Map<String, String> signSagemakerIfConfigured(
      String provider,
      ProviderOptions providerOptions,
      String providerUrl,
      String method,
      byte[] bodyBytes,
      Map<String, String> providerHeaders) {
    if (!"sagemaker".equalsIgnoreCase(provider)) {
      return providerHeaders;
    }
    String accessKeyId = providerOptions.stringValue("awsAccessKeyId");
    String secretAccessKey = providerOptions.stringValue("awsSecretAccessKey");
    if (!hasText(accessKeyId) || !hasText(secretAccessKey)) {
      return providerHeaders;
    }
    Map<String, String> signingHeaders = new LinkedHashMap<>(providerHeaders);
    Map<String, String> sagemakerHeaders = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : signingHeaders.entrySet()) {
      if (!entry.getKey().toLowerCase(Locale.ROOT).startsWith("x-amzn-sagemaker-")) {
        sagemakerHeaders.put(entry.getKey(), entry.getValue());
      }
    }
    Map<String, String> signedHeaders = AwsSigV4Signer.sign(
        method,
        providerUrl,
        bodyBytes,
        sagemakerHeaders,
        "sagemaker",
        firstText(providerOptions.stringValue("awsRegion"), "us-east-1"),
        accessKeyId,
        secretAccessKey,
        providerOptions.stringValue("awsSessionToken"));
    for (Map.Entry<String, String> entry : signingHeaders.entrySet()) {
      if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("x-amzn-sagemaker-")) {
        signedHeaders.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
      }
    }
    return signedHeaders;
  }

  private static String azureAiAccessToken(ProviderOptions providerOptions) {
    String authMode = providerOptions.stringValue("azureAuthMode");
    if (!hasText(authMode)) {
      return "";
    }
    String normalized = authMode.trim().toLowerCase(Locale.ROOT);
    if ("entra".equals(normalized)) {
      String tenantId = providerOptions.stringValue("azureEntraTenantId");
      String clientId = providerOptions.stringValue("azureEntraClientId");
      String clientSecret = providerOptions.stringValue("azureEntraClientSecret");
      if (!hasText(tenantId) || !hasText(clientId) || !hasText(clientSecret)) {
        return "";
      }
      String scope = firstText(
          providerOptions.stringValue("azureEntraScope"),
          "https://cognitiveservices.azure.com/.default");
      String tokenUrl = azureEntraTokenUrl(tenantId);
      String cacheKey = "azure-entra:" + tokenUrl + ":" + clientId + ":" + scope;
      return cachedToken(cacheKey, () -> postFormForToken(
          tokenUrl,
          Map.of("content-type", "application/x-www-form-urlencoded"),
          Map.of(
              "grant_type", "client_credentials",
              "client_id", clientId,
              "client_secret", clientSecret,
              "scope", scope)));
    }
    if ("managed".equals(normalized)) {
      String resource = firstText(
          providerOptions.stringValue("azureEntraScope"),
          "https://cognitiveservices.azure.com/");
      String managedTokenUrl = azureManagedIdentityTokenUrl(resource, providerOptions.stringValue("azureManagedClientId"));
      String cacheKey = "azure-managed:" + managedTokenUrl;
      return cachedToken(cacheKey, () -> getForToken(managedTokenUrl, Map.of("Metadata", "true")));
    }
    if ("workload".equals(normalized)) {
      String authorityHost = firstText(
          providerOptions.stringValue("azureAuthorityHost"),
          System.getenv("AZURE_AUTHORITY_HOST"));
      String tenantId = firstText(providerOptions.stringValue("azureTenantId"), System.getenv("AZURE_TENANT_ID"));
      String clientId = firstText(
          providerOptions.stringValue("azureWorkloadClientId"),
          System.getenv("AZURE_CLIENT_ID"));
      String federatedToken = firstText(
          providerOptions.stringValue("azureFederatedToken"),
          readTextFile(System.getenv("AZURE_FEDERATED_TOKEN_FILE")));
      if (!hasText(authorityHost) || !hasText(tenantId) || !hasText(clientId) || !hasText(federatedToken)) {
        return "";
      }
      String scope = firstText(
          providerOptions.stringValue("azureEntraScope"),
          "https://cognitiveservices.azure.com/.default");
      String tokenUrl = trimTrailingSlash(authorityHost) + "/" + encodePathSegment(tenantId) + "/oauth2/v2.0/token";
      String cacheKey = "azure-workload:" + tokenUrl + ":" + clientId + ":" + scope + ":" + federatedToken.hashCode();
      return cachedToken(cacheKey, () -> postFormForToken(
          tokenUrl,
          Map.of("content-type", "application/x-www-form-urlencoded"),
          Map.of(
              "grant_type", "client_credentials",
              "client_id", clientId,
              "client_assertion", federatedToken,
              "client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
              "scope", scope)));
    }
    return "";
  }

  private static String vertexServiceAccountAccessToken(ProviderOptions providerOptions) {
    Object serviceAccount = providerOptions.get("vertexServiceAccountJson");
    if (!(serviceAccount instanceof Map<?, ?> serviceAccountMap)) {
      return "";
    }
    String clientEmail = stringMapValue(serviceAccountMap, "client_email");
    String privateKey = stringMapValue(serviceAccountMap, "private_key");
    String privateKeyId = stringMapValue(serviceAccountMap, "private_key_id");
    String projectId = stringMapValue(serviceAccountMap, "project_id");
    if (!hasText(clientEmail) || !hasText(privateKey)) {
      return "";
    }
    String tokenUrl = firstText(
        providerOptions.stringValue("vertexTokenUrl"),
        System.getProperty("modelgate.vertex.tokenUrl", "https://oauth2.googleapis.com/token"));
    String scope = "https://www.googleapis.com/auth/cloud-platform";
    String cacheKey = "vertex:" + projectId + ":" + privateKeyId + ":" + clientEmail + ":" + tokenUrl;
    return cachedToken(cacheKey, () -> {
      long issuedAt = Instant.now().getEpochSecond();
      String assertion = serviceAccountJwt(clientEmail, privateKeyId, privateKey, tokenUrl, scope, issuedAt, issuedAt + 3600);
      return postFormForToken(
          tokenUrl,
          Map.of("content-type", "application/x-www-form-urlencoded"),
          Map.of(
              "grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer",
              "assertion", assertion));
    });
  }

  private static String cachedToken(String cacheKey, TokenSupplier supplier) {
    CachedToken cached = TOKEN_CACHE.get(cacheKey);
    long now = Instant.now().getEpochSecond();
    if (cached != null && cached.expiresAtEpochSeconds() - 30 > now) {
      return cached.value();
    }
    try {
      TokenResponse response = supplier.get();
      if (!hasText(response.accessToken())) {
        return "";
      }
      long ttl = response.expiresInSeconds() > 0 ? response.expiresInSeconds() : 3000;
      TOKEN_CACHE.put(cacheKey, new CachedToken(response.accessToken(), now + ttl));
      return response.accessToken();
    } catch (Exception exception) {
      return "";
    }
  }

  private static TokenResponse postFormForToken(String tokenUrl, Map<String, String> headers, Map<String, String> form)
      throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(tokenUrl))
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(formBody(form)));
    headers.forEach(builder::header);
    HttpResponse<String> response = TOKEN_HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return parseTokenResponse(response.body());
  }

  private static TokenResponse getForToken(String tokenUrl, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(tokenUrl))
        .timeout(Duration.ofSeconds(10))
        .GET();
    headers.forEach(builder::header);
    HttpResponse<String> response = TOKEN_HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return parseTokenResponse(response.body());
  }

  private static TokenResponse parseTokenResponse(String body) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(body);
    return new TokenResponse(root.path("access_token").asText(""), root.path("expires_in").asLong(3000));
  }

  private static String serviceAccountJwt(
      String clientEmail,
      String privateKeyId,
      String privateKeyPem,
      String audience,
      String scope,
      long issuedAt,
      long expiresAt) throws Exception {
    ObjectNode header = OBJECT_MAPPER.createObjectNode();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    if (hasText(privateKeyId)) {
      header.put("kid", privateKeyId);
    }
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.put("iss", clientEmail);
    payload.put("sub", clientEmail);
    payload.put("aud", audience);
    payload.put("iat", issuedAt);
    payload.put("exp", expiresAt);
    payload.put("scope", scope);
    String unsigned = base64Url(OBJECT_MAPPER.writeValueAsBytes(header))
        + "."
        + base64Url(OBJECT_MAPPER.writeValueAsBytes(payload));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(privateKeyFromPem(privateKeyPem));
    signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
    return unsigned + "." + base64Url(signature.sign());
  }

  private static PrivateKey privateKeyFromPem(String privateKeyPem) throws Exception {
    String normalized = privateKeyPem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s+", "");
    byte[] der = Base64.getDecoder().decode(normalized);
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private static String azureEntraTokenUrl(String tenantId) {
    String template = System.getProperty(
        "modelgate.azure.entra.tokenUrlTemplate",
        "https://login.microsoftonline.com/%s/oauth2/v2.0/token");
    return template.formatted(encodePathSegment(tenantId));
  }

  private static String azureManagedIdentityTokenUrl(String resource, String clientId) {
    String base = System.getProperty(
        "modelgate.azure.managed.tokenUrl",
        "http://169.254.169.254/metadata/identity/oauth2/token");
    String query = "api-version=2018-02-01&resource=" + encodeQueryComponent(resource);
    if (hasText(clientId)) {
      query += "&client_id=" + encodeQueryComponent(clientId);
    }
    return base + (base.contains("?") ? "&" : "?") + query;
  }

  private static String formBody(Map<String, String> form) {
    return form.entrySet().stream()
        .map(entry -> encodeQueryComponent(entry.getKey()) + "=" + encodeQueryComponent(entry.getValue()))
        .reduce((left, right) -> left + "&" + right)
        .orElse("");
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String stringMapValue(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private static String readTextFile(String path) {
    if (!hasText(path)) {
      return "";
    }
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    } catch (Exception exception) {
      return "";
    }
  }

  private static void addProviderOptionHeaders(
      Map<String, String> providerHeaders, String provider, ProviderOptions providerOptions) {
    addProviderOptionHeaders(providerHeaders, provider, providerOptions, null, "");
  }

  private static void addProviderOptionHeaders(
      Map<String, String> providerHeaders,
      String provider,
      ProviderOptions providerOptions,
      String providerEndpointPath) {
    addProviderOptionHeaders(providerHeaders, provider, providerOptions, providerEndpointPath, "");
  }

  private static void addProviderOptionHeaders(
      Map<String, String> providerHeaders,
      String provider,
      ProviderOptions providerOptions,
      String providerEndpointPath,
      String requestBody) {
    if (provider == null || provider.isBlank()) {
      return;
    }

    switch (provider.toLowerCase(Locale.ROOT)) {
      case "openai" -> addOpenAiHeaders(providerHeaders, providerOptions);
      case "anthropic" -> addAnthropicHeaders(providerHeaders, providerOptions);
      case "deepbricks" -> addDeepbricksHeaders(providerHeaders, providerOptions);
      case "azure-openai" -> addAzureOpenAiHeaders(providerHeaders, providerOptions);
      case "azure-ai" -> addAzureAiInferenceHeaders(providerHeaders, providerOptions);
      case "vertex-ai" -> addVertexAiHeaders(providerHeaders, providerOptions, requestBody);
      case "openrouter" -> addOpenRouterHeaders(providerHeaders);
      case "sagemaker" -> addSagemakerHeaders(providerHeaders, providerOptions);
      case "stability-ai" -> addStabilityHeaders(providerHeaders, providerEndpointPath);
      case "predibase" -> providerHeaders.put("Accept", "application/json");
      default -> {
      }
    }
  }

  private static void addOpenAiHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    putIfHasText(providerHeaders, "OpenAI-Organization", providerOptions.stringValue("openaiOrganization"));
    putIfHasText(providerHeaders, "OpenAI-Project", providerOptions.stringValue("openaiProject"));
    putIfHasText(providerHeaders, "OpenAI-Beta", providerOptions.stringValue("openaiBeta"));
  }

  private static void addAnthropicHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    providerHeaders.put("anthropic-version", hasText(providerOptions.stringValue("anthropicVersion"))
        ? providerOptions.stringValue("anthropicVersion")
        : "2023-06-01");
    putIfHasText(providerHeaders, "anthropic-beta", providerOptions.stringValue("anthropicBeta"));
  }

  private static void addDeepbricksHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    putIfHasText(providerHeaders, "Deepbricks-Organization", providerOptions.stringValue("openaiOrganization"));
    putIfHasText(providerHeaders, "Deepbricks-Project", providerOptions.stringValue("openaiProject"));
  }

  private static void addAzureOpenAiHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    putIfHasText(providerHeaders, "OpenAI-Beta", providerOptions.stringValue("openaiBeta"));
  }

  private static void addVertexAiHeaders(
      Map<String, String> providerHeaders, ProviderOptions providerOptions, String requestBody) {
    if (providerOptions.asMap().containsKey("anthropicBeta")) {
      putIfHasText(providerHeaders, "anthropic-beta", providerOptions.stringValue("anthropicBeta"));
      return;
    }
    ObjectNode root = parseObjectBody(requestBody);
    if (root != null) {
      putIfHasText(providerHeaders, "anthropic-beta", root.path("anthropic_beta").asText(null));
    }
  }

  private static void addAzureAiInferenceHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    providerHeaders.put("extra-parameters", providerOptions.stringValue("azureExtraParameters") == null
        ? "drop"
        : providerOptions.stringValue("azureExtraParameters"));
    putIfHasText(providerHeaders, "azureml-model-deployment", providerOptions.stringValue("azureDeploymentName"));
    if (isAzureAiAnthropicRequest(providerOptions)) {
      providerHeaders.put("anthropic-version", hasText(providerOptions.stringValue("anthropicVersion"))
          ? providerOptions.stringValue("anthropicVersion")
          : "2023-06-01");
    }
  }

  private static void addOpenRouterHeaders(Map<String, String> providerHeaders) {
    providerHeaders.put("HTTP-Referer", "https://portkey.ai/");
    providerHeaders.put("X-Title", "ModelGate");
  }

  private static void addSagemakerHeaders(Map<String, String> providerHeaders, ProviderOptions providerOptions) {
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-custom-attributes",
        providerOptions.stringValue("amznSagemakerCustomAttributes"));
    putIfHasText(providerHeaders, "x-amzn-sagemaker-target-model", providerOptions.stringValue("amznSagemakerTargetModel"));
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-target-variant",
        providerOptions.stringValue("amznSagemakerTargetVariant"));
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-target-container-hostname",
        providerOptions.stringValue("amznSagemakerTargetContainerHostname"));
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-inference-id",
        providerOptions.stringValue("amznSagemakerInferenceId"));
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-enable-explanations",
        providerOptions.stringValue("amznSagemakerEnableExplanations"));
    putIfHasText(
        providerHeaders,
        "x-amzn-sagemaker-inference-component",
        providerOptions.stringValue("amznSagemakerInferenceComponent"));
    putIfHasText(providerHeaders, "x-amzn-sagemaker-session-id", providerOptions.stringValue("amznSagemakerSessionId"));
  }

  private static void addStabilityHeaders(Map<String, String> providerHeaders, String providerEndpointPath) {
    String path = splitEndpoint(providerEndpointPath == null ? "" : providerEndpointPath).path();
    if (path.startsWith("/v2beta/")) {
      String contentType = providerHeaders.get("content-type");
      if (!hasText(contentType) || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
        providerHeaders.put("content-type", "multipart/form-data; boundary=" + STABILITY_MULTIPART_BOUNDARY);
      }
      providerHeaders.put("accept", "application/json");
    }
  }

  private static ProviderOptions effectiveProviderOptions(Target target, GatewayConfig inheritedConfig) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (inheritedConfig != null && inheritedConfig.providerOptions() != null) {
      merged.putAll(inheritedConfig.providerOptions().asMap());
    }
    if (target.providerOptions() != null) {
      merged.putAll(target.providerOptions().asMap());
    }
    return new ProviderOptions(merged);
  }

  private static String effectiveApiKey(Target target, GatewayConfig inheritedConfig) {
    if (hasText(target.apiKey())) {
      return target.apiKey();
    }
    return inheritedConfig == null ? null : inheritedConfig.apiKey();
  }

  private static void putIfHasText(Map<String, String> headers, String name, String value) {
    if (hasText(value)) {
      headers.put(name, value);
    }
  }

  private static boolean isAzureAiAnthropicRequest(ProviderOptions providerOptions) {
    String azureFoundryUrl = providerOptions.stringValue("azureFoundryUrl");
    String urlToFetch = providerOptions.stringValue("urlToFetch");
    return containsIgnoreCase(azureFoundryUrl, "anthropic") || containsIgnoreCase(urlToFetch, "anthropic");
  }

  private static boolean containsIgnoreCase(String value, String expected) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
  }

  private static String bearerValue(String value) {
    return value.regionMatches(true, 0, "Bearer ", 0, 7) ? "Bearer " + value.substring(7) : "Bearer " + value;
  }

  private static Map<String, String> proxyHeaders(
      GatewayConfig config, Map<String, String> headers, String method) {
    Map<String, String> providerHeaders = providerHeaders(config, headers, method);
    addSafeProxyHeaders(providerHeaders, headers);
    return providerHeaders;
  }

  private static Map<String, String> proxyHeaders(
      Target target, GatewayConfig inheritedConfig, Map<String, String> headers, String method) {
    Map<String, String> providerHeaders = providerHeaders(target, inheritedConfig, headers, method);
    addSafeProxyHeaders(providerHeaders, headers);
    return providerHeaders;
  }

  private static RetryPolicy retryPolicy(RetrySettings retrySettings) {
    if (retrySettings == null || retrySettings.attempts() <= 0) {
      return new RetryPolicy(0, List.of(), false);
    }
    List<Integer> statusCodes = retrySettings.onStatusCodes().isEmpty()
        ? DEFAULT_RETRY_STATUS_CODES
        : retrySettings.onStatusCodes();
    return new RetryPolicy(retrySettings.attempts(), statusCodes, retrySettings.useRetryAfterHeader());
  }

  private static RetryPolicy retryPolicy(Target target, GatewayConfig inheritedConfig) {
    if (target.retry() != null) {
      return retryPolicy(target.retry());
    }
    return inheritedConfig == null
        ? new RetryPolicy(0, List.of(), false)
        : retryPolicy(inheritedConfig.retry());
  }

  private static Duration timeout(GatewayConfig config, Map<String, String> headers) {
    String headerTimeout = GatewayHeaders.value(headers, "request-timeout");
    if (headerTimeout != null && !headerTimeout.isBlank()) {
      return Duration.ofMillis(Long.parseLong(headerTimeout));
    }
    if (config != null && config.requestTimeoutMillis() > 0) {
      return Duration.ofMillis(config.requestTimeoutMillis());
    }
    return DEFAULT_TIMEOUT;
  }

  private static Duration timeout(Target target, GatewayConfig inheritedConfig, Map<String, String> headers) {
    String headerTimeout = GatewayHeaders.value(headers, "request-timeout");
    if (headerTimeout != null && !headerTimeout.isBlank()) {
      return Duration.ofMillis(Long.parseLong(headerTimeout));
    }
    if (target.requestTimeoutMillis() > 0) {
      return Duration.ofMillis(target.requestTimeoutMillis());
    }
    if (inheritedConfig != null && inheritedConfig.requestTimeoutMillis() > 0) {
      return Duration.ofMillis(inheritedConfig.requestTimeoutMillis());
    }
    return DEFAULT_TIMEOUT;
  }

  private static Duration timeout(Map<String, String> headers) {
    String headerTimeout = GatewayHeaders.value(headers, "request-timeout");
    if (headerTimeout != null && !headerTimeout.isBlank()) {
      return Duration.ofMillis(Long.parseLong(headerTimeout));
    }
    return DEFAULT_TIMEOUT;
  }

  private static String defaultBaseUrl(String provider) {
    return ProviderCatalog.defaultBaseUrl(provider);
  }

  private static String baseUrl(
      String provider,
      String endpointPath,
      String headerCustomHost,
      String configCustomHost,
      ProviderOptions providerOptions) {
    if (hasText(headerCustomHost)) {
      return headerCustomHost;
    }
    if (hasText(configCustomHost)) {
      return configCustomHost;
    }
    if ("workers-ai".equalsIgnoreCase(provider)) {
      String accountId = providerOptions.stringValue("workersAiAccountId");
      if (hasText(accountId)) {
        String normalizedAccountId = accountId.trim();
        if (!WORKERS_AI_ACCOUNT_ID.matcher(normalizedAccountId).matches()) {
          throw new IllegalArgumentException("Invalid workers_ai_account_id");
        }
        return "https://api.cloudflare.com/client/v4/accounts/" + normalizedAccountId + "/ai/run";
      }
    }
    if ("azure-openai".equalsIgnoreCase(provider)) {
      String resourceName = providerOptions.stringValue("resourceName");
      if (hasText(resourceName)) {
        String normalizedResourceName = resourceName.trim();
        if (!AZURE_OPENAI_RESOURCE_NAME.matcher(normalizedResourceName).matches()) {
          throw new IllegalArgumentException("Invalid resource_name");
        }
        return "https://" + normalizedResourceName + ".openai.azure.com/openai";
      }
    }
    if ("azure-ai".equalsIgnoreCase(provider)) {
      String azureFoundryUrl = providerOptions.stringValue("azureFoundryUrl");
      if (hasText(azureFoundryUrl)) {
        if (isAzureAiNonInferencePath(splitEndpoint(endpointPath == null ? "" : endpointPath).path())) {
          return originOf(azureFoundryUrl) + "/openai";
        }
        return trimTrailingSlash(azureFoundryUrl);
      }
      return "https://models.inference.ai.azure.com";
    }
    if ("bedrock".equalsIgnoreCase(provider)) {
      return "https://bedrock-runtime." + firstText(providerOptions.stringValue("awsRegion"), "us-east-1")
          + ".amazonaws.com";
    }
    if ("vertex-ai".equalsIgnoreCase(provider)) {
      if (isVertexStoragePath(splitEndpoint(endpointPath == null ? "" : endpointPath).path())) {
        return "https://storage.googleapis.com";
      }
      String region = firstText(providerOptions.stringValue("vertexRegion"), "us-central1");
      return "global".equalsIgnoreCase(region)
          ? "https://aiplatform.googleapis.com"
          : "https://" + region + "-aiplatform.googleapis.com";
    }
    if ("sagemaker".equalsIgnoreCase(provider)) {
      return "https://runtime.sagemaker." + firstText(providerOptions.stringValue("awsRegion"), "us-east-1")
          + ".amazonaws.com";
    }
    if ("meshy".equalsIgnoreCase(provider)) {
      String path = splitEndpoint(endpointPath == null ? "" : endpointPath).path();
      String version = path.contains("text-to-3d") ? "v2" : "v1";
      return "https://api.meshy.ai/openapi/" + version;
    }
    if ("oracle".equalsIgnoreCase(provider)) {
      return "https://inference.generativeai."
          + firstText(providerOptions.stringValue("oracleRegion"), "us-ashburn-1")
          + ".oci.oraclecloud.com";
    }
    return defaultBaseUrl(provider);
  }

  private static String providerEndpointPath(
      String provider, String endpointPath, String method, String baseUrl, String body, ProviderOptions providerOptions) {
    EndpointParts parts = splitEndpoint(endpointPath);
    String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    if ("anthropic".equals(normalizedProvider)) {
      if ("/v1/chat/completions".equals(parts.path()) || "/v1/messages".equals(parts.path())) {
        return parts.withPath("/messages");
      }
      if ("/v1/messages/count_tokens".equals(parts.path())) {
        return parts.withPath("/messages/count_tokens");
      }
    }
    if ("azure-openai".equals(normalizedProvider)) {
      String azurePath = azureOpenAiEndpointPath(parts, providerOptions);
      if (azurePath != null) {
        return azurePath;
      }
    }
    if ("azure-ai".equals(normalizedProvider)) {
      String azureAiPath = azureAiInferenceEndpointPath(parts, providerOptions);
      if (azureAiPath != null) {
        return azureAiPath;
      }
    }
    if ("workers-ai".equals(normalizedProvider)) {
      return parts.withPath("/" + modelFromBody(body));
    }
    if ("bedrock".equals(normalizedProvider)) {
      return parts.withPath(bedrockEndpointPath(parts.path(), body));
    }
    if ("vertex-ai".equals(normalizedProvider)) {
      return vertexEndpointPath(parts, body, providerOptions);
    }
    if ("google".equals(normalizedProvider)) {
      String googlePath = googleEndpointPath(parts.path(), body, providerOptions);
      if (googlePath != null) {
        return parts.withPath(googlePath);
      }
    }
    if ("palm".equals(normalizedProvider)) {
      String palmPath = palmEndpointPath(parts.path(), body, providerOptions);
      if (palmPath != null) {
        return parts.withPath(palmPath);
      }
    }
    if ("sagemaker".equals(normalizedProvider) && parts.path().startsWith("/v1/")) {
      return parts.withPath(parts.path().substring(3));
    }
    if ("cohere".equals(normalizedProvider)) {
      String coherePath = cohereEndpointPath(parts.path(), method);
      if (coherePath != null) {
        return parts.withPath(coherePath);
      }
    }
    if ("fireworks-ai".equals(normalizedProvider)) {
      String fireworksPath = fireworksEndpointPath(parts.path(), body);
      if (fireworksPath != null) {
        return parts.withPath(fireworksPath);
      }
    }
    if ("stability-ai".equals(normalizedProvider)) {
      String stabilityPath = stabilityEndpointPath(parts.path(), body);
      if (stabilityPath != null) {
        return parts.withPath(stabilityPath);
      }
    }
    if ("huggingface".equals(normalizedProvider)) {
      String huggingFacePath = huggingFaceEndpointPath(parts.path(), body, providerOptions);
      if (huggingFacePath != null) {
        return parts.withPath(huggingFacePath);
      }
    }
    if ("perplexity-ai".equals(normalizedProvider) && "/v1/chat/completions".equals(parts.path())) {
      return parts.withPath("/chat/completions");
    }
    if ("ai21".equals(normalizedProvider)) {
      String ai21Path = ai21EndpointPath(parts.path(), body);
      if (ai21Path != null) {
        return parts.withPath(ai21Path);
      }
    }
    if ("nomic".equals(normalizedProvider) && "/v1/embeddings".equals(parts.path())) {
      return parts.withPath("/embedding/text");
    }
    if ("predibase".equals(normalizedProvider) && "/v1/chat/completions".equals(parts.path())) {
      return parts.withPath(predibaseChatEndpointPath(body));
    }
    if ("reka-ai".equals(normalizedProvider) && "/v1/chat/completions".equals(parts.path())) {
      return parts.withPath("/chat");
    }
    if ("segmind".equals(normalizedProvider) && "/v1/images/generations".equals(parts.path())) {
      return parts.withPath("/" + modelFromBody(body));
    }
    if ("bytez".equals(normalizedProvider) && "/v1/chat/completions".equals(parts.path())) {
      return parts.withPath(bytezEndpointPath(body));
    }
    if ("lepton".equals(normalizedProvider)) {
      String leptonPath = leptonEndpointPath(parts.path());
      if (leptonPath != null) {
        return parts.withPath(leptonPath);
      }
    }
    if ("oracle".equals(normalizedProvider) && "/v1/chat/completions".equals(parts.path())) {
      return parts.withPath("/" + firstText(providerOptions.stringValue("oracleApiVersion"), "20231130")
          + "/actions/chat");
    }
    if ("triton".equals(normalizedProvider) && "/v1/completions".equals(parts.path())) {
      return parts.withPath("/generate");
    }
    if ("mistral-ai".equals(normalizedProvider)
        && "true".equalsIgnoreCase(providerOptions.stringValue("mistralFimCompletion"))) {
      return parts.withPath("/fim/completions");
    }
    if ("ollama".equals(normalizedProvider) && "/v1/embeddings".equals(parts.path())) {
      return parts.withPath("/api/embeddings");
    }
    if (shouldStripOpenAiVersionPrefix(normalizedProvider, baseUrl) && parts.path().startsWith("/v1/")) {
      return parts.withPath(parts.path().substring(3));
    }
    return endpointPath;
  }

  private static String providerMethod(String provider, String endpointPath, String method) {
    String normalizedMethod = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
    if ("vertex-ai".equalsIgnoreCase(provider)) {
      String path = splitEndpoint(endpointPath == null ? "" : endpointPath).path();
      if ("GET".equals(normalizedMethod) && path.matches("/v1/files/[^/]+")) {
        return "HEAD";
      }
    }
    return normalizedMethod;
  }

  private static String bedrockEndpointPath(String path, String body) {
    String model = bedrockModelWithoutRegion(modelFromBody(body));
    String encodedModel = encodePathSegment(model);
    if ("/v1/messages/count_tokens".equals(path)) {
      return "/model/" + encodedModel + "/count-tokens";
    }
    if ("/v1/chat/completions".equals(path) && !BEDROCK_INVOKE_MODELS.contains(model)) {
      return "/model/" + encodedModel + (booleanFromBody(body, "stream") ? "/converse-stream" : "/converse");
    }
    if ("/v1/chat/completions".equals(path)
        || "/v1/completions".equals(path)
        || "/v1/embeddings".equals(path)
        || "/v1/images/generations".equals(path)) {
      return "/model/" + encodedModel
          + (booleanFromBody(body, "stream") ? "/invoke-with-response-stream" : "/invoke");
    }
    return path;
  }

  private static String azureOpenAiEndpointPath(EndpointParts parts, ProviderOptions providerOptions) {
    String path = parts.path();
    if (!path.startsWith("/v1/")) {
      return null;
    }
    String apiVersion = firstText(providerOptions.stringValue("apiVersion"), "2024-02-01");
    if ("v1".equalsIgnoreCase(apiVersion)) {
      return new EndpointParts(path, parts.query()).withPath(path);
    }
    if (isAzureOpenAiRootPath(path)) {
      String mappedPath = path.startsWith("/v1/") ? path.substring(3) : path;
      return new EndpointParts(mappedPath, appendQuery(parts.query(), "api-version=" + encodeQueryComponent(apiVersion)))
          .withPath(mappedPath);
    }
    String deploymentId = providerOptions.stringValue("deploymentId");
    if (!hasText(deploymentId)) {
      return null;
    }
    String operation = path.substring("/v1/".length());
    String mappedPath = "/deployments/" + encodePathSegment(deploymentId.trim()) + "/" + operation;
    return new EndpointParts(mappedPath, appendQuery(parts.query(), "api-version=" + encodeQueryComponent(apiVersion)))
        .withPath(mappedPath);
  }

  private static String vertexEndpointPath(EndpointParts parts, String body, ProviderOptions providerOptions) {
    String path = parts.path();
    String nonInferencePath = vertexNonInferenceEndpointPath(parts, providerOptions);
    if (nonInferencePath != null) {
      return nonInferencePath;
    }
    String model = modelFromBody(body);
    if (model.isBlank()) {
      return path;
    }
    String[] modelParts = vertexModelAndProvider(model);
    String provider = modelParts[0];
    String providerModel = modelParts[1];
    String routeVersion = "meta".equals(provider) ? "v1beta1" : "v1";
    String projectId = vertexProjectId(providerOptions);
    String region = firstText(providerOptions.stringValue("vertexRegion"), "us-central1");
    String projectRoute = "/" + routeVersion + "/projects/" + projectId + "/locations/" + region;
    boolean stream = booleanFromBody(body, "stream");
    if ("/v1/messages/count_tokens".equals(path) && "anthropic".equals(provider)) {
      return projectRoute + "/publishers/anthropic/models/count-tokens:rawPredict";
    }
    if ("/v1/chat/completions".equals(path)) {
      if ("google".equals(provider)) {
        return projectRoute
            + "/publishers/google/models/"
            + providerModel
            + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
      }
      if ("anthropic".equals(provider) || "mistralai".equals(provider)) {
        return projectRoute
            + "/publishers/"
            + provider
            + "/models/"
            + providerModel
            + (stream ? ":streamRawPredict" : ":rawPredict");
      }
      if ("meta".equals(provider)) {
        return projectRoute + "/endpoints/openapi/chat/completions";
      }
      if ("endpoints".equals(provider)) {
        return projectRoute + "/endpoints/" + providerModel + "/chat/completions";
      }
    }
    if ("/v1/embeddings".equals(path) || "/v1/images/generations".equals(path)) {
      return projectRoute + "/publishers/" + provider + "/models/" + providerModel + ":predict";
    }
    return path;
  }

  private static String azureAiInferenceEndpointPath(EndpointParts parts, ProviderOptions providerOptions) {
    String path = parts.path();
    String azureApiVersion = firstText(providerOptions.stringValue("azureApiVersion"), "");
    String apiVersionQuery = hasText(azureApiVersion)
        ? "api-version=" + encodeQueryComponent(azureApiVersion)
        : "";
    boolean anthropic = isAzureAiAnthropicRequest(providerOptions);
    String mappedPath = switch (path) {
      case "/v1/chat/completions" -> anthropic ? "/v1/messages" : "/chat/completions";
      case "/v1/completions" -> "/completions";
      case "/v1/embeddings" -> "/embeddings";
      case "/v1/messages" -> "/v1/messages";
      case "/v1/realtime" -> "/realtime";
      case "/v1/images/generations" -> "/images/generations";
      case "/v1/images/edits" -> "/images/edits";
      case "/v1/audio/speech" -> "/audio/speech";
      case "/v1/audio/transcriptions" -> "/audio/transcriptions";
      case "/v1/audio/translations" -> "/audio/translations";
      default -> path.startsWith("/v1/") ? path.substring(3) : path;
    };
    if ("/v1/messages".equals(mappedPath)) {
      return mappedPath;
    }
    return new EndpointParts(mappedPath, appendQuery(parts.query(), apiVersionQuery)).withPath(mappedPath);
  }

  private static boolean isAzureAiNonInferencePath(String path) {
    if (path == null) {
      return false;
    }
    if (AZURE_AI_NON_INFERENCE_PATHS.contains(path)) {
      return true;
    }
    return path.startsWith("/v1/batches/")
        || path.startsWith("/v1/files/")
        || path.startsWith("/v1/fine_tuning/jobs/");
  }

  private static boolean isAzureOpenAiRootPath(String path) {
    if (path == null) {
      return false;
    }
    if (AZURE_OPENAI_ROOT_PATHS.contains(path)) {
      return true;
    }
    return path.startsWith("/v1/batches/")
        || path.startsWith("/v1/files/")
        || path.startsWith("/v1/fine_tuning/jobs/")
        || path.startsWith("/v1/responses/");
  }

  private static String vertexNonInferenceEndpointPath(EndpointParts parts, ProviderOptions providerOptions) {
    String path = parts.path();
    String projectId = vertexProjectId(providerOptions);
    String region = firstText(providerOptions.stringValue("vertexRegion"), "us-central1");
    String projectRoute = "/v1/projects/" + projectId + "/locations/" + region;
    if ("/v1/batches".equals(path)) {
      return new EndpointParts(
          projectRoute + "/batchPredictionJobs",
          vertexPaginationQuery(parts.query()))
          .withPath(projectRoute + "/batchPredictionJobs");
    }
    if (path.startsWith("/v1/batches/") && path.endsWith("/cancel")) {
      String id = path.substring("/v1/batches/".length(), path.length() - "/cancel".length());
      return projectRoute + "/batchPredictionJobs/" + encodePathSegment(id) + ":cancel";
    }
    if (path.startsWith("/v1/batches/") && !path.endsWith("/output")) {
      return projectRoute + "/batchPredictionJobs/" + encodePathSegment(path.substring("/v1/batches/".length()));
    }
    if ("/v1/fine_tuning/jobs".equals(path)) {
      return new EndpointParts(
          projectRoute + "/tuningJobs",
          hasText(parts.query()) ? vertexPaginationQuery(parts.query()) : null)
          .withPath(projectRoute + "/tuningJobs");
    }
    if (path.startsWith("/v1/fine_tuning/jobs/") && path.endsWith("/cancel")) {
      String id = path.substring("/v1/fine_tuning/jobs/".length(), path.length() - "/cancel".length());
      return projectRoute + "/tuningJobs/" + encodePathSegment(id) + ":cancel";
    }
    if (path.startsWith("/v1/fine_tuning/jobs/")) {
      return projectRoute + "/tuningJobs/" + encodePathSegment(path.substring("/v1/fine_tuning/jobs/".length()));
    }
    if (path.matches("/v1/files/[^/]+/content")) {
      String id = path.substring("/v1/files/".length(), path.length() - "/content".length());
      return vertexStorageObjectPath(id);
    }
    if (path.matches("/v1/files/[^/]+")) {
      String id = path.substring("/v1/files/".length());
      return vertexStorageObjectPath(id);
    }
    return null;
  }

  private static String googleEndpointPath(String path, String body, ProviderOptions providerOptions) {
    String model = modelFromBody(body);
    if (model.isBlank()) {
      return null;
    }
    String routeVersion = model.contains("gemini-2.0-flash-thinking-exp") ? "v1alpha" : "v1beta";
    String apiKey = providerOptions.stringValue("apiKey");
    String keyQuery = hasText(apiKey) ? "?key=" + encodeQueryComponent(apiKey) : "";
    boolean stream = booleanFromBody(body, "stream");
    return switch (path) {
      case "/v1/chat/completions" -> "/" + routeVersion + "/models/" + model
          + (stream ? ":streamGenerateContent" : ":generateContent")
          + keyQuery;
      case "/v1/embeddings" -> "/" + routeVersion + "/models/" + model + ":embedContent" + keyQuery;
      default -> null;
    };
  }

  private static String palmEndpointPath(String path, String body, ProviderOptions providerOptions) {
    String model = modelFromBody(body);
    if (model.isBlank()) {
      return null;
    }
    String apiKey = providerOptions.stringValue("apiKey");
    String keyQuery = hasText(apiKey) ? "?key=" + encodeQueryComponent(apiKey) : "";
    return switch (path) {
      case "/v1/chat/completions" -> "/models/" + model + ":generateMessage" + keyQuery;
      case "/v1/completions" -> "/models/" + model + ":generateText" + keyQuery;
      case "/v1/embeddings" -> "/models/" + model + ":embedText" + keyQuery;
      default -> null;
    };
  }

  private static String stabilityEndpointPath(String path, String body) {
    if (!"/v1/images/generations".equals(path)) {
      return null;
    }
    String model = modelFromBody(body);
    if (model.startsWith("stable-diffusion-xl-v")) {
      return "/v1/generation/" + model + "/text-to-image";
    }
    return "/v2beta/stable-image/generate/" + model;
  }

  private static String huggingFaceEndpointPath(String path, String body, ProviderOptions providerOptions) {
    String modelPath = hasText(providerOptions.stringValue("huggingfaceBaseUrl"))
        ? ""
        : "/models/" + modelFromBody(body);
    return switch (path) {
      case "/v1/chat/completions" -> modelPath + "/v1/chat/completions";
      case "/v1/completions" -> modelPath + "/v1/completions";
      default -> null;
    };
  }

  private static String cohereEndpointPath(String path, String method) {
    String normalizedMethod = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
    if ("/v1/chat/completions".equals(path)) {
      return "/v2/chat";
    }
    if ("/v1/completions".equals(path)) {
      return "/v1/generate";
    }
    if ("/v1/embeddings".equals(path)) {
      return "/v2/embed";
    }
    if ("/v1/files".equals(path)) {
      if ("POST".equals(normalizedMethod)) {
        return "/v1/datasets?name=portkey-" + UUID.randomUUID() + "&type=embed-input&keep_fields=custom_id,id";
      }
      return "/v1/datasets";
    }
    if (path.startsWith("/v1/files/")) {
      return "/v1/datasets/" + encodePathSegment(path.substring("/v1/files/".length()));
    }
    if ("/v1/batches".equals(path)) {
      return "/v1/embed-jobs";
    }
    if (path.matches("/v1/batches/[^/]+/cancel")) {
      String id = path.substring("/v1/batches/".length(), path.length() - "/cancel".length());
      return "/v1/embed-jobs/" + encodePathSegment(id) + "/cancel";
    }
    if (path.startsWith("/v1/batches/") && !path.endsWith("/output")) {
      return "/v1/embed-jobs/" + encodePathSegment(path.substring("/v1/batches/".length()));
    }
    return null;
  }

  private static String fireworksEndpointPath(String path, String body) {
    return switch (path) {
      case "/v1/chat/completions" -> "/chat/completions";
      case "/v1/completions" -> "/completions";
      case "/v1/embeddings" -> "/embeddings";
      case "/v1/images/generations" -> "/image_generation/" + modelFromBody(body);
      default -> null;
    };
  }

  private static String ai21EndpointPath(String path, String body) {
    String model = modelFromBody(body);
    return switch (path) {
      case "/v1/chat/completions" -> "/" + model + "/chat";
      case "/v1/completions" -> "/" + model + "/complete";
      case "/v1/embeddings" -> "/embed";
      default -> null;
    };
  }

  private static String predibaseChatEndpointPath(String body) {
    ObjectNode root = parseObjectBody(body);
    String user = root == null ? "" : root.path("user").asText("");
    String model = root == null ? "" : root.path("model").asText("");
    return "/" + user + "/deployments/v2/llms/" + beforeColon(model) + "/v1/chat/completions";
  }

  private static String bytezEndpointPath(String body) {
    ObjectNode root = parseObjectBody(body);
    String version = root == null ? "2" : root.path("version").asText("2");
    String model = root == null ? "" : root.path("model").asText("");
    return "/models/v" + version + "/" + model;
  }

  private static String leptonEndpointPath(String path) {
    return switch (path) {
      case "/v1/chat/completions" -> "/api/v1/chat/completions";
      case "/v1/completions" -> "/api/v1/completions";
      case "/v1/audio/transcriptions" -> "/api/v1/audio/transcriptions";
      default -> null;
    };
  }

  private static String modelFromBody(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      return OBJECT_MAPPER.readTree(body).path("model").asText("");
    } catch (Exception exception) {
      return "";
    }
  }

  private static String bedrockModelWithoutRegion(String model) {
    if (model == null || model.isBlank()) {
      return "";
    }
    String trimmed = decodePathSegment(model.trim());
    int dot = trimmed.indexOf('.');
    if (dot <= 0) {
      return trimmed;
    }
    String prefix = trimmed.substring(0, dot);
    if (Set.of("us", "eu", "apac", "au", "ca", "jp", "global").contains(prefix)) {
      return trimmed.substring(dot + 1);
    }
    return trimmed;
  }

  private static String decodePathSegment(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      return value;
    }
  }

  private static String routingBodyForRawBody(Map<String, String> headers, byte[] bodyBytes) {
    String contentType = headers.getOrDefault("content-type", "");
    if (!contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
      return "";
    }
    String model = multipartField(contentType, new String(bodyBytes, StandardCharsets.UTF_8), "model");
    if (!hasText(model)) {
      return "";
    }
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("model", model);
    return writeBody(root, "");
  }

  private static String multipartField(String contentType, String body, String fieldName) {
    String boundary = multipartBoundary(contentType);
    if (!hasText(boundary)) {
      return "";
    }
    String delimiter = "--" + boundary;
    String[] parts = body.split(Pattern.quote(delimiter));
    for (String part : parts) {
      int separator = part.indexOf("\r\n\r\n");
      int separatorLength = 4;
      if (separator < 0) {
        separator = part.indexOf("\n\n");
        separatorLength = 2;
      }
      if (separator < 0) {
        continue;
      }
      String headers = part.substring(0, separator);
      if (!headers.contains("name=\"" + fieldName + "\"")) {
        continue;
      }
      String value = part.substring(separator + separatorLength);
      return value.replaceFirst("\\r?\\n--?$", "").trim();
    }
    return "";
  }

  private static String multipartBoundary(String contentType) {
    for (String parameter : contentType.split(";")) {
      String trimmed = parameter.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
        String value = trimmed.substring("boundary=".length()).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          return value.substring(1, value.length() - 1);
        }
        return value;
      }
    }
    return "";
  }

  private static String providerBody(String provider, String endpointPath, String body, ProviderOptions providerOptions) {
    String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    EndpointParts parts = splitEndpoint(endpointPath);
    if ("anthropic".equals(normalizedProvider)
        && "/messages".equals(parts.path())) {
      return ANTHROPIC_TRANSFORMER.transformRequest(body);
    }
    if ("anthropic".equals(normalizedProvider) && "/messages/count_tokens".equals(parts.path())) {
      return transformAnthropicNativeMessagesBody(body);
    }
    if ("workers-ai".equals(normalizedProvider)) {
      return transformWorkersAiBody(parts.path(), body);
    }
    if ("bedrock".equals(normalizedProvider)) {
      return transformBedrockBody(parts.path(), body);
    }
    if ("azure-ai".equals(normalizedProvider)) {
      return transformAzureAiBody(parts.path(), body, providerOptions);
    }
    if ("vertex-ai".equals(normalizedProvider)) {
      return transformVertexBody(parts.path(), body);
    }
    if ("google".equals(normalizedProvider)) {
      return transformGoogleBody(parts.path(), body);
    }
    if ("palm".equals(normalizedProvider)) {
      return transformPalmBody(parts.path(), body);
    }
    if ("cohere".equals(normalizedProvider)) {
      return transformCohereBody(parts.path(), body);
    }
    if ("mistral-ai".equals(normalizedProvider)) {
      return transformMistralBody(parts.path(), body);
    }
    if ("stability-ai".equals(normalizedProvider)) {
      return transformStabilityBody(parts.path(), body);
    }
    if ("recraft-ai".equals(normalizedProvider)) {
      return transformRecraftBody(parts.path(), body);
    }
    if ("huggingface".equals(normalizedProvider)) {
      return transformOpenAiEdgeBody(body, true, false);
    }
    if ("deepseek".equals(normalizedProvider)) {
      return transformOpenAiEdgeBody(body, true, false);
    }
    if ("openrouter".equals(normalizedProvider)) {
      return transformOpenAiEdgeBody(body, true, true);
    }
    if ("perplexity-ai".equals(normalizedProvider)) {
      return transformPerplexityBody(body);
    }
    if ("ai21".equals(normalizedProvider)) {
      return transformAi21Body(parts.path(), body);
    }
    if (("voyage".equals(normalizedProvider) || "jina".equals(normalizedProvider))
        && "/embeddings".equals(parts.path())) {
      return transformEmbeddingInputToArray(body);
    }
    if ("nomic".equals(normalizedProvider) && "/embedding/text".equals(parts.path())) {
      return transformNomicEmbeddingBody(body);
    }
    if ("predibase".equals(normalizedProvider) && parts.path().endsWith("/v1/chat/completions")) {
      return transformPredibaseChatBody(body);
    }
    if ("reka-ai".equals(normalizedProvider) && "/chat".equals(parts.path())) {
      return transformRekaChatBody(body);
    }
    if ("segmind".equals(normalizedProvider) && parts.path().startsWith("/") && !parts.path().startsWith("/v1/")) {
      return transformSegmindImageBody(body);
    }
    if ("bytez".equals(normalizedProvider) && parts.path().startsWith("/models/v")) {
      return transformBytezChatBody(body);
    }
    if ("oracle".equals(normalizedProvider) && parts.path().endsWith("/actions/chat")) {
      return transformOracleChatBody(body, providerOptions);
    }
    if ("triton".equals(normalizedProvider) && "/generate".equals(parts.path())) {
      return transformTritonCompletionBody(body);
    }
    if ("ollama".equals(normalizedProvider)) {
      return transformOllamaBody(parts.path(), body);
    }
    if ("together-ai".equals(normalizedProvider)) {
      return transformTogetherAiBody(body);
    }
    return body;
  }

  private static String transformWorkersAiBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    root.remove("model");
    convertDeveloperMessagesToSystem(root);
    moveField(root, "max_completion_tokens", "max_tokens");
    if (root.has("size") && root.path("size").asText("").contains("x")) {
      String[] sizeParts = root.path("size").asText("").toLowerCase(Locale.ROOT).split("x", 2);
      root.put("width", Integer.parseInt(sizeParts[0]));
      root.put("height", Integer.parseInt(sizeParts[1]));
      root.remove("size");
    }
    if (root.has("steps")) {
      root.set("num_steps", root.path("steps").deepCopy());
    }
    if (root.has("input")) {
      root.set("text", arrayFromInput(root.get("input")));
      root.remove("input");
    }
    if (root.has("prompt") && root.path("prompt").isTextual() && !root.has("negative_prompt") && !root.has("width")) {
      root.put("prompt", "\n\nHuman: " + root.path("prompt").asText() + "\n\nAssistant:");
    }
    return writeBody(root, body);
  }

  private static String transformAzureAiBody(String path, String body, ProviderOptions providerOptions) {
    if ("/v1/messages".equals(path) && isAzureAiAnthropicRequest(providerOptions)) {
      return ANTHROPIC_TRANSFORMER.transformRequest(body);
    }
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if ("/chat/completions".equals(path) || "/completions".equals(path) || "/embeddings".equals(path)) {
      convertDeveloperMessagesToSystem(root);
      clampNumber(root, "temperature", 0.0d, 2.0d);
      clampNumber(root, "top_p", 0.0d, 1.0d);
      clampNumber(root, "presence_penalty", -2.0d, 2.0d);
      clampNumber(root, "frequency_penalty", -2.0d, 2.0d);
      clampNumber(root, "max_tokens", 0.0d, null);
      clampNumber(root, "max_completion_tokens", 0.0d, null);
      return writeBody(root, body);
    }
    return body;
  }

  private static String transformAnthropicNativeMessagesBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    ObjectNode transformed = OBJECT_MAPPER.createObjectNode();
    for (String field : ANTHROPIC_MESSAGES_FIELDS) {
      JsonNode value = root.get(field);
      if (value != null && !value.isNull()) {
        transformed.set(field, value.deepCopy());
      }
    }
    return writeBody(transformed, body);
  }

  private static String transformBedrockBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (path.endsWith("/count-tokens")) {
      return transformBedrockMessagesCountTokensBody(root, body);
    }
    if (!path.endsWith("/converse") && !path.endsWith("/converse-stream")) {
      if (path.endsWith("/invoke") || path.endsWith("/invoke-with-response-stream")) {
        return transformBedrockInvokeBody(root, body);
      }
      return writeBody(root, body);
    }

    return transformBedrockConverseBody(root, body);
  }

  private static String transformBedrockConverseBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ArrayNode messages = OBJECT_MAPPER.createArrayNode();
    ArrayNode system = OBJECT_MAPPER.createArrayNode();
    addBedrockSystemContent(system, root.path("system"));
    JsonNode inputMessages = root.path("messages");
    if (inputMessages.isArray()) {
      for (JsonNode message : inputMessages) {
        String role = message.path("role").asText("");
        if ("system".equals(role) || "developer".equals(role)) {
          addBedrockSystemContent(system, message.path("content"));
          continue;
        }
        ObjectNode outMessage = OBJECT_MAPPER.createObjectNode();
        outMessage.put("role", "assistant".equals(role) ? "assistant" : "user");
        ArrayNode content = OBJECT_MAPPER.createArrayNode();
        addBedrockContentBlocks(content, message.path("content"));
        outMessage.set("content", content);
        messages.add(outMessage);
      }
    }
    out.set("messages", messages);
    if (!system.isEmpty()) {
      out.set("system", system);
    }

    ObjectNode inferenceConfig = OBJECT_MAPPER.createObjectNode();
    copyField(root, inferenceConfig, "max_tokens", "maxTokens");
    copyField(root, inferenceConfig, "max_completion_tokens", "maxTokens");
    copyField(root, inferenceConfig, "temperature", "temperature");
    copyField(root, inferenceConfig, "top_p", "topP");
    JsonNode stop = root.get("stop");
    if (stop != null && !stop.isNull()) {
      inferenceConfig.set("stopSequences", stringArray(stop));
    }
    JsonNode stopSequences = root.get("stop_sequences");
    if (stopSequences != null && !stopSequences.isNull()) {
      inferenceConfig.set("stopSequences", stringArray(stopSequences));
    }
    if (!inferenceConfig.isEmpty()) {
      out.set("inferenceConfig", inferenceConfig);
    }
    ObjectNode toolConfig = bedrockToolConfig(root);
    if (toolConfig != null && !toolConfig.isEmpty()) {
      out.set("toolConfig", toolConfig);
    }
    copyField(root, out, "guardrailConfig", "guardrailConfig");
    copyField(root, out, "guardrail_config", "guardrailConfig");
    copyField(root, out, "additionalModelResponseFieldPaths", "additionalModelResponseFieldPaths");
    copyField(root, out, "additional_model_response_field_paths", "additionalModelResponseFieldPaths");
    copyField(root, out, "additionalModelRequestFields", "additionalModelRequestFields");
    copyField(root, out, "additional_model_request_fields", "additionalModelRequestFields");
    copyField(root, out, "performance_config", "performanceConfig");
    copyField(root, out, "metadata", "requestMetadata");
    return writeBody(out, fallbackBody);
  }

  private static String transformBedrockMessagesCountTokensBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ObjectNode input = OBJECT_MAPPER.createObjectNode();
    out.set("input", input);
    String model = bedrockModelWithoutRegion(root.path("model").asText(""));
    if (model.startsWith("anthropic.")) {
      ObjectNode invokeModel = OBJECT_MAPPER.createObjectNode();
      ObjectNode anthropicBody = OBJECT_MAPPER.createObjectNode();
      for (String field : ANTHROPIC_MESSAGES_FIELDS) {
        JsonNode value = root.get(field);
        if (value != null && !value.isNull() && !"model".equals(field)) {
          anthropicBody.set(field, value.deepCopy());
        }
      }
      if (root.has("anthropic_version") && !root.path("anthropic_version").isNull()) {
        anthropicBody.set("anthropic_version", root.path("anthropic_version").deepCopy());
      }
      putDefault(anthropicBody, "anthropic_version", "bedrock-2023-05-31");
      putDefault(anthropicBody, "max_tokens", 10);
      invokeModel.put("body", Base64.getEncoder()
          .encodeToString(writeBody(anthropicBody, "{}").getBytes(StandardCharsets.UTF_8)));
      input.set("invokeModel", invokeModel);
      return writeBody(out, fallbackBody);
    }
    try {
      input.set("converse", OBJECT_MAPPER.readTree(transformBedrockConverseBody(root, fallbackBody)));
      return writeBody(out, fallbackBody);
    } catch (Exception exception) {
      return fallbackBody;
    }
  }

  private static String transformBedrockInvokeBody(ObjectNode root, String fallbackBody) {
    String model = root.path("model").asText("");
    if (root.has("input")) {
      return transformBedrockEmbeddingBody(root, fallbackBody, model);
    }
    if (root.has("prompt")) {
      if (model.startsWith("anthropic.")) {
        return transformBedrockAnthropicCompletionBody(root, fallbackBody);
      }
      if (model.startsWith("amazon.titan-text") || model.startsWith("amazon.titan-tg")) {
        return transformBedrockTitanCompletionBody(root, fallbackBody);
      }
      if (model.startsWith("cohere.")) {
        moveField(root, "top_p", "p");
        moveField(root, "n", "num_generations");
        moveField(root, "stop", "end_sequences");
        root.remove("model");
        return writeBody(root, fallbackBody);
      }
      if (model.startsWith("ai21.")) {
        moveField(root, "max_tokens", "maxTokens");
        moveField(root, "top_p", "topP");
        moveField(root, "stop", "stopSequences");
        root.remove("model");
        return writeBody(root, fallbackBody);
      }
    }
    if (root.has("prompt") && model.contains("stable-diffusion")) {
      return transformStabilityBody("/v1/generation/" + model + "/text-to-image", writeBody(root, fallbackBody));
    }
    root.remove("model");
    return writeBody(root, fallbackBody);
  }

  private static String transformBedrockEmbeddingBody(ObjectNode root, String fallbackBody, String model) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    JsonNode input = root.get("input");
    if (model.startsWith("cohere.")) {
      ArrayNode texts = OBJECT_MAPPER.createArrayNode();
      ArrayNode images = OBJECT_MAPPER.createArrayNode();
      Iterable<JsonNode> items = input != null && input.isArray() ? input : List.of(input);
      for (JsonNode item : items) {
        if (item == null || item.isNull()) {
          continue;
        }
        if (item.isTextual()) {
          texts.add(item.asText());
        } else if (item.has("text")) {
          texts.add(item.path("text").asText(""));
        } else if (item.path("image").has("base64")) {
          images.add(item.path("image").path("base64").asText(""));
        }
      }
      if (!texts.isEmpty()) {
        out.set("texts", texts);
      }
      if (!images.isEmpty()) {
        out.set("images", images);
      }
      copyField(root, out, "input_type", "input_type");
      copyField(root, out, "truncate", "truncate");
      if (root.has("encoding_format")) {
        out.set("embedding_types", stringArray(root.get("encoding_format")));
      }
      return writeBody(out, fallbackBody);
    }

    if (input != null) {
      if (input.isTextual()) {
        out.put("inputText", input.asText());
      } else if (input.isArray() && !input.isEmpty()) {
        JsonNode first = input.get(0);
        if (first.isTextual()) {
          out.put("inputText", first.asText());
        } else if (first.has("text")) {
          out.put("inputText", first.path("text").asText(""));
        }
        if (first.path("image").has("base64")) {
          out.put("inputImage", first.path("image").path("base64").asText(""));
        }
      }
    }
    copyField(root, out, "dimensions", input != null && input.isArray() ? "embeddingConfig" : "dimensions");
    if (input != null && input.isArray() && out.has("embeddingConfig")) {
      ObjectNode embeddingConfig = OBJECT_MAPPER.createObjectNode();
      embeddingConfig.set("outputEmbeddingLength", out.remove("embeddingConfig"));
      out.set("embeddingConfig", embeddingConfig);
    }
    if (root.has("encoding_format")
        && !Set.of("amazon.titan-embed-g1-text-02", "amazon.titan-embed-text-v1", "amazon.titan-embed-image-v1")
            .contains(model)) {
      out.set("embeddingTypes", stringArray(root.get("encoding_format")));
    }
    copyField(root, out, "normalize", "normalize");
    return writeBody(out, fallbackBody);
  }

  private static String transformBedrockAnthropicCompletionBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    out.put("prompt", "\n\nHuman: " + root.path("prompt").asText("") + "\n\nAssistant:");
    copyField(root, out, "max_tokens", "max_tokens_to_sample");
    copyField(root, out, "temperature", "temperature");
    copyField(root, out, "top_p", "top_p");
    copyField(root, out, "top_k", "top_k");
    if (root.has("stop")) {
      out.set("stop_sequences", stringArray(root.get("stop")));
    }
    if (root.has("user")) {
      ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
      metadata.set("user_id", root.path("user").deepCopy());
      out.set("metadata", metadata);
    }
    return writeBody(out, fallbackBody);
  }

  private static String transformBedrockTitanCompletionBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    copyField(root, out, "prompt", "inputText");
    ObjectNode config = OBJECT_MAPPER.createObjectNode();
    copyField(root, config, "temperature", "temperature");
    copyField(root, config, "top_p", "topP");
    copyField(root, config, "max_tokens", "maxTokenCount");
    if (root.has("stop")) {
      config.set("stopSequences", stringArray(root.get("stop")));
    }
    if (!config.isEmpty()) {
      out.set("textGenerationConfig", config);
    }
    return writeBody(out, fallbackBody);
  }

  private static void addBedrockTextContent(ArrayNode out, JsonNode content) {
    if (content == null || content.isMissingNode() || content.isNull()) {
      return;
    }
    if (content.isTextual()) {
      ObjectNode item = OBJECT_MAPPER.createObjectNode();
      item.put("text", content.asText());
      out.add(item);
      return;
    }
    if (content.isArray()) {
      for (JsonNode part : content) {
        if ("text".equals(part.path("type").asText())) {
          ObjectNode item = OBJECT_MAPPER.createObjectNode();
          item.put("text", part.path("text").asText(""));
          out.add(item);
        }
      }
    }
  }

  private static void addBedrockSystemContent(ArrayNode out, JsonNode content) {
    if (content == null || content.isMissingNode() || content.isNull()) {
      return;
    }
    if (content.isTextual()) {
      ObjectNode item = OBJECT_MAPPER.createObjectNode();
      item.put("text", content.asText());
      out.add(item);
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode part : content) {
      ObjectNode item = OBJECT_MAPPER.createObjectNode();
      item.put("text", part.path("text").asText(""));
      out.add(item);
      addBedrockCachePoint(out, part);
    }
  }

  private static void addBedrockContentBlocks(ArrayNode out, JsonNode content) {
    if (content == null || content.isMissingNode() || content.isNull()) {
      return;
    }
    if (content.isTextual()) {
      ObjectNode item = OBJECT_MAPPER.createObjectNode();
      item.put("text", content.asText());
      out.add(item);
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode part : content) {
      String type = part.path("type").asText("");
      switch (type) {
        case "text" -> {
          ObjectNode item = OBJECT_MAPPER.createObjectNode();
          item.put("text", part.path("text").asText(""));
          out.add(item);
          addBedrockCachePoint(out, part);
        }
        case "image" -> addBedrockImageBlock(out, part);
        case "document" -> addBedrockDocumentBlock(out, part);
        case "thinking" -> addBedrockThinkingBlock(out, part);
        case "redacted_thinking" -> addBedrockRedactedThinkingBlock(out, part);
        case "tool_use" -> addBedrockToolUseBlock(out, part);
        case "tool_result" -> addBedrockToolResultBlock(out, part);
        default -> {
        }
      }
    }
  }

  private static void addBedrockImageBlock(ArrayNode out, JsonNode part) {
    JsonNode source = part.path("source");
    String sourceType = source.path("type").asText("");
    if (!"base64".equals(sourceType) && !"url".equals(sourceType)) {
      return;
    }
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    ObjectNode image = item.putObject("image");
    image.put("format", mediaFormat(source.path("media_type").asText("")));
    ObjectNode imageSource = image.putObject("source");
    if ("base64".equals(sourceType)) {
      imageSource.set("bytes", source.path("data").deepCopy());
    } else {
      imageSource.putObject("s3Location").set("uri", source.path("url").deepCopy());
    }
    out.add(item);
    addBedrockCachePoint(out, part);
  }

  private static void addBedrockDocumentBlock(ArrayNode out, JsonNode part) {
    JsonNode source = part.path("source");
    String sourceType = source.path("type").asText("");
    if (!"base64".equals(sourceType) && !"url".equals(sourceType)) {
      return;
    }
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    ObjectNode document = item.putObject("document");
    document.put("format", mediaFormat(firstText(source.path("media_type").asText(""), "application/pdf")));
    ObjectNode documentSource = document.putObject("source");
    if ("base64".equals(sourceType)) {
      documentSource.set("bytes", source.path("data").deepCopy());
    } else {
      documentSource.putObject("s3Location").set("uri", source.path("url").deepCopy());
    }
    out.add(item);
    addBedrockCachePoint(out, part);
  }

  private static void addBedrockThinkingBlock(ArrayNode out, JsonNode part) {
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    ObjectNode reasoningText = item.putObject("reasoningContent").putObject("reasoningText");
    reasoningText.set("text", part.path("thinking").deepCopy());
    if (part.has("signature")) {
      reasoningText.set("signature", part.path("signature").deepCopy());
    }
    out.add(item);
  }

  private static void addBedrockRedactedThinkingBlock(ArrayNode out, JsonNode part) {
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    item.putObject("reasoningContent").set("redactedContent", part.path("data").deepCopy());
    out.add(item);
  }

  private static void addBedrockToolUseBlock(ArrayNode out, JsonNode part) {
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    ObjectNode toolUse = item.putObject("toolUse");
    toolUse.set("input", part.path("input").deepCopy());
    toolUse.set("name", part.path("name").deepCopy());
    toolUse.set("toolUseId", part.path("id").deepCopy());
    out.add(item);
    addBedrockCachePoint(out, part);
  }

  private static void addBedrockToolResultBlock(ArrayNode out, JsonNode part) {
    ObjectNode item = OBJECT_MAPPER.createObjectNode();
    ObjectNode toolResult = item.putObject("toolResult");
    toolResult.set("toolUseId", part.path("tool_use_id").deepCopy());
    toolResult.put("status", part.path("is_error").asBoolean(false) ? "error" : "success");
    ArrayNode content = OBJECT_MAPPER.createArrayNode();
    JsonNode sourceContent = part.path("content");
    if (sourceContent.isTextual()) {
      ObjectNode text = OBJECT_MAPPER.createObjectNode();
      text.put("text", sourceContent.asText());
      content.add(text);
    } else if (sourceContent.isArray()) {
      for (JsonNode itemContent : sourceContent) {
        if ("text".equals(itemContent.path("type").asText(""))) {
          ObjectNode text = OBJECT_MAPPER.createObjectNode();
          text.put("text", itemContent.path("text").asText(""));
          content.add(text);
        } else if ("image".equals(itemContent.path("type").asText(""))) {
          addBedrockImageBlock(content, itemContent);
        }
      }
    }
    toolResult.set("content", content);
    out.add(item);
    addBedrockCachePoint(out, part);
  }

  private static void addBedrockCachePoint(ArrayNode out, JsonNode part) {
    if (part.has("cache_control") && !part.path("cache_control").isNull()) {
      ObjectNode cachePoint = OBJECT_MAPPER.createObjectNode();
      cachePoint.putObject("cachePoint").put("type", "default");
      out.add(cachePoint);
    }
  }

  private static String mediaFormat(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      return "";
    }
    int slash = mediaType.lastIndexOf('/');
    return slash >= 0 ? mediaType.substring(slash + 1) : mediaType;
  }

  private static ObjectNode bedrockToolConfig(ObjectNode root) {
    JsonNode toolsNode = root.path("tools");
    if (!toolsNode.isArray() || toolsNode.isEmpty()) {
      return null;
    }
    ObjectNode toolConfig = OBJECT_MAPPER.createObjectNode();
    ArrayNode tools = OBJECT_MAPPER.createArrayNode();
    for (JsonNode tool : toolsNode) {
      JsonNode function = tool.path("function");
      if (!function.isObject()) {
        continue;
      }
      ObjectNode toolSpec = OBJECT_MAPPER.createObjectNode();
      toolSpec.set("name", function.path("name").deepCopy());
      if (function.has("description")) {
        toolSpec.set("description", function.path("description").deepCopy());
      }
      ObjectNode inputSchema = OBJECT_MAPPER.createObjectNode();
      inputSchema.set("json", function.path("parameters").deepCopy());
      toolSpec.set("inputSchema", inputSchema);
      ObjectNode toolEntry = OBJECT_MAPPER.createObjectNode();
      toolEntry.set("toolSpec", toolSpec);
      tools.add(toolEntry);
    }
    if (!tools.isEmpty()) {
      toolConfig.set("tools", tools);
    }
    JsonNode toolChoice = root.path("tool_choice");
    if (toolChoice.isObject() && toolChoice.path("function").has("name")) {
      ObjectNode choice = OBJECT_MAPPER.createObjectNode();
      ObjectNode tool = OBJECT_MAPPER.createObjectNode();
      tool.set("name", toolChoice.path("function").path("name").deepCopy());
      choice.set("tool", tool);
      toolConfig.set("toolChoice", choice);
    } else if ("required".equals(toolChoice.asText())) {
      ObjectNode choice = OBJECT_MAPPER.createObjectNode();
      choice.set("any", OBJECT_MAPPER.createObjectNode());
      toolConfig.set("toolChoice", choice);
    } else if ("auto".equals(toolChoice.asText())) {
      ObjectNode choice = OBJECT_MAPPER.createObjectNode();
      choice.set("auto", OBJECT_MAPPER.createObjectNode());
      toolConfig.set("toolChoice", choice);
    }
    return toolConfig;
  }

  private static String transformVertexBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (path.endsWith("/batchPredictionJobs")) {
      return transformVertexCreateBatchBody(root, body);
    }
    if (path.endsWith("/tuningJobs")) {
      return transformVertexCreateFineTuneBody(root, body);
    }
    if (path.endsWith("/publishers/anthropic/models/count-tokens:rawPredict")) {
      return transformVertexAnthropicMessagesCountTokensBody(root, body);
    }
    if (path.endsWith(":predict")) {
      return transformVertexPredictBody(root, body);
    }
    if (!path.contains(":generateContent") && !path.contains(":streamGenerateContent")) {
      return body;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ArrayNode contents = OBJECT_MAPPER.createArrayNode();
    JsonNode inputMessages = root.path("messages");
    if (inputMessages.isArray()) {
      for (JsonNode message : inputMessages) {
        String role = message.path("role").asText("");
        if ("system".equals(role) || "developer".equals(role)) {
          if (!out.has("systemInstruction")) {
            ObjectNode systemInstruction = OBJECT_MAPPER.createObjectNode();
            systemInstruction.put("role", "system");
            ArrayNode parts = OBJECT_MAPPER.createArrayNode();
            addVertexParts(parts, message.path("content"));
            systemInstruction.set("parts", parts);
            out.set("systemInstruction", systemInstruction);
          }
          continue;
        }
        ObjectNode outMessage = OBJECT_MAPPER.createObjectNode();
        outMessage.put("role", "assistant".equals(role) ? "model" : "user");
        ArrayNode parts = OBJECT_MAPPER.createArrayNode();
        addVertexParts(parts, message.path("content"));
        outMessage.set("parts", parts);
        contents.add(outMessage);
      }
    }
    out.set("contents", contents);
    ObjectNode generationConfig = OBJECT_MAPPER.createObjectNode();
    copyField(root, generationConfig, "temperature", "temperature");
    copyField(root, generationConfig, "top_p", "topP");
    copyField(root, generationConfig, "top_k", "topK");
    copyField(root, generationConfig, "max_tokens", "maxOutputTokens");
    copyField(root, generationConfig, "max_completion_tokens", "maxOutputTokens");
    copyField(root, generationConfig, "seed", "seed");
    JsonNode stop = root.get("stop");
    if (stop != null && !stop.isNull()) {
      generationConfig.set("stopSequences", stringArray(stop));
    }
    JsonNode responseFormat = root.path("response_format");
    if ("json_object".equals(responseFormat.path("type").asText())
        || "json_schema".equals(responseFormat.path("type").asText())) {
      generationConfig.put("responseMimeType", "application/json");
      if ("json_schema".equals(responseFormat.path("type").asText())) {
        JsonNode schema = responseFormat.at("/json_schema/schema").isMissingNode()
            ? responseFormat.path("json_schema")
            : responseFormat.at("/json_schema/schema");
        if (schema.isObject()) {
          ObjectNode schemaCopy = schema.deepCopy();
          recursivelyDeleteUnsupportedParameters(schemaCopy);
          generationConfig.set("responseSchema", schemaCopy);
        }
      }
    }
    copyField(root, generationConfig, "logprobs", "responseLogprobs");
    copyField(root, generationConfig, "top_logprobs", "logprobs");
    if (root.path("modalities").isArray()) {
      ArrayNode modalities = OBJECT_MAPPER.createArrayNode();
      root.path("modalities").forEach(modality -> modalities.add(modality.asText("").toUpperCase(Locale.ROOT)));
      generationConfig.set("responseModalities", modalities);
    }
    if (hasText(root.path("reasoning_effort").asText(null))
        && !"none".equals(root.path("reasoning_effort").asText())) {
      ObjectNode thinkingConfig = OBJECT_MAPPER.createObjectNode();
      thinkingConfig.set("thinkingLevel", root.path("reasoning_effort").deepCopy());
      generationConfig.set("thinkingConfig", thinkingConfig);
    }
    JsonNode thinking = root.path("thinking");
    if (thinking.isObject()) {
      ObjectNode thinkingConfig = OBJECT_MAPPER.createObjectNode();
      int budgetTokens = thinking.path("budget_tokens").asInt(0);
      thinkingConfig.put("include_thoughts", "enabled".equals(thinking.path("type").asText()) && budgetTokens > 0);
      thinkingConfig.set("thinking_budget", thinking.path("budget_tokens").deepCopy());
      generationConfig.set("thinking_config", thinkingConfig);
    }
    JsonNode imageConfig = root.path("image_config");
    if (imageConfig.isObject()) {
      ObjectNode vertexImageConfig = OBJECT_MAPPER.createObjectNode();
      copyField((ObjectNode) imageConfig, vertexImageConfig, "aspect_ratio", "aspectRatio");
      copyField((ObjectNode) imageConfig, vertexImageConfig, "image_size", "imageSize");
      if (!vertexImageConfig.isEmpty()) {
        generationConfig.set("imageConfig", vertexImageConfig);
      }
    }
    if (!generationConfig.isEmpty()) {
      out.set("generationConfig", generationConfig);
    }
    ArrayNode tools = vertexTools(root);
    if (!tools.isEmpty()) {
      out.set("tools", tools);
    }
    ObjectNode toolConfig = vertexToolConfig(root);
    if (toolConfig != null && !toolConfig.isEmpty()) {
      out.set("tool_config", toolConfig);
    }
    copyField(root, out, "safety_settings", "safety_settings");
    copyField(root, out, "labels", "labels");
    return writeBody(out, body);
  }

  private static String transformVertexCreateBatchBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    JsonNode providerOptions = root.path("provider_options");
    if (providerOptions.isObject()) {
      out.setAll((ObjectNode) providerOptions);
    }

    String model = root.path("model").asText("");
    if (hasText(model)) {
      String[] modelParts = vertexModelAndProvider(model);
      out.put("model", "publishers/" + modelParts[0] + "/models/" + modelParts[1]);
    }

    String inputFileId = decodeComponent(root.path("input_file_id").asText(""));
    if (hasText(inputFileId)) {
      ObjectNode inputConfig = out.putObject("inputConfig");
      inputConfig.put("instancesFormat", "jsonl");
      inputConfig.putObject("gcsSource").put("uris", inputFileId);
    }

    String outputPrefix = root.has("output_data_config")
        ? decodeComponent(root.path("output_data_config").asText(""))
        : containingFolder(inputFileId);
    if (hasText(outputPrefix)) {
      ObjectNode outputConfig = out.putObject("outputConfig");
      outputConfig.put("predictionsFormat", "jsonl");
      outputConfig.putObject("gcsDestination").put("outputUriPrefix", outputPrefix);
    }

    out.put("displayName", firstText(root.path("job_name").asText(null), UUID.randomUUID().toString()));
    ObjectNode instanceConfig = out.putObject("instanceConfig");
    ArrayNode excludedFields = instanceConfig.putArray("excludedFields");
    excludedFields.add("requestId");
    instanceConfig.putArray("includedFields");
    instanceConfig.put("instanceType", "object");
    return writeBody(out, fallbackBody);
  }

  private static String transformVertexCreateFineTuneBody(ObjectNode root, String fallbackBody) {
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    copyField(root, out, "model", "baseModel");
    copyField(root, out, "suffix", "tunedModelDisplayName");

    ObjectNode supervised = OBJECT_MAPPER.createObjectNode();
    String trainingFile = decodeComponent(root.path("training_file").asText(""));
    if (hasText(trainingFile)) {
      supervised.put("training_dataset_uri", trainingFile);
    }
    String validationFile = decodeComponent(root.path("validation_file").asText(""));
    if (hasText(validationFile)) {
      supervised.put("validation_dataset_uri", validationFile);
    }
    JsonNode hyperparameters = vertexFineTuneHyperparameters(root);
    ObjectNode hyperParameters = OBJECT_MAPPER.createObjectNode();
    copyField((ObjectNode) hyperparameters, hyperParameters, "n_epochs", "epochCount");
    copyField((ObjectNode) hyperparameters, hyperParameters, "learning_rate_multiplier", "learningRateMultiplier");
    copyField((ObjectNode) hyperparameters, hyperParameters, "batch_size", "adapterSize");
    if (!hyperParameters.isEmpty()) {
      supervised.set("hyperParameters", hyperParameters);
    }
    if (!supervised.isEmpty()) {
      out.set("supervisedTuningSpec", supervised);
    }
    return writeBody(out, fallbackBody);
  }

  private static JsonNode vertexFineTuneHyperparameters(ObjectNode root) {
    JsonNode method = root.path("method");
    if (method.isObject()) {
      String type = method.path("type").asText("");
      JsonNode nested = method.path(type).path("hyperparameters");
      if (nested.isObject()) {
        return nested;
      }
    }
    JsonNode hyperparameters = root.path("hyperparameters");
    return hyperparameters.isObject() ? hyperparameters : OBJECT_MAPPER.createObjectNode();
  }

  private static String transformVertexAnthropicMessagesCountTokensBody(ObjectNode root, String fallbackBody) {
    ObjectNode transformed = OBJECT_MAPPER.createObjectNode();
    for (String field : ANTHROPIC_MESSAGES_FIELDS) {
      JsonNode value = root.get(field);
      if (value != null && !value.isNull()) {
        transformed.set(field, value.deepCopy());
      }
    }
    String model = transformed.path("model").asText("");
    if (model.startsWith("anthropic.")) {
      transformed.put("model", model.substring("anthropic.".length()));
    }
    return writeBody(transformed, fallbackBody);
  }

  private static String transformGoogleBody(String path, String body) {
    if (path.contains(":generateContent") || path.contains(":streamGenerateContent")) {
      return transformVertexBody(path, body);
    }
    if (!path.contains(":embedContent")) {
      return body;
    }
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ObjectNode content = OBJECT_MAPPER.createObjectNode();
    ArrayNode parts = OBJECT_MAPPER.createArrayNode();
    JsonNode input = root.get("input");
    if (input != null) {
      Iterable<JsonNode> items = input.isArray() ? input : List.of(input);
      for (JsonNode item : items) {
        ObjectNode part = OBJECT_MAPPER.createObjectNode();
        part.put("text", item.isTextual() ? item.asText() : item.path("text").asText(""));
        parts.add(part);
      }
    }
    content.set("parts", parts);
    out.set("content", content);
    return writeBody(out, body);
  }

  private static String transformPalmBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    root.remove("model");
    if (path.contains(":generateMessage")) {
      JsonNode messages = root.path("messages");
      ObjectNode prompt = OBJECT_MAPPER.createObjectNode();
      ArrayNode palmMessages = OBJECT_MAPPER.createArrayNode();
      if (messages.isArray()) {
        for (JsonNode message : messages) {
          ObjectNode palmMessage = palmMessages.addObject();
          String role = "developer".equals(message.path("role").asText())
              ? "system"
              : message.path("role").asText("");
          palmMessage.put("author", role);
          palmMessage.put("content", message.path("content").asText(""));
        }
      }
      prompt.set("messages", palmMessages);
      if (root.has("examples")) {
        prompt.set("examples", root.path("examples").deepCopy());
      }
      if (root.has("context")) {
        prompt.set("context", root.path("context").deepCopy());
      }
      root.set("prompt", prompt);
      root.remove("messages");
      moveField(root, "top_p", "topP");
      moveField(root, "top_k", "topK");
      moveField(root, "n", "candidateCount");
      moveField(root, "max_tokens", "maxOutputTokens");
      moveField(root, "max_completion_tokens", "maxOutputTokens");
      moveField(root, "stop", "stopSequences");
      return writeBody(root, body);
    }
    if (path.contains(":generateText")) {
      ObjectNode prompt = OBJECT_MAPPER.createObjectNode();
      prompt.put("text", root.path("prompt").asText(""));
      root.set("prompt", prompt);
      moveField(root, "top_p", "topP");
      moveField(root, "top_k", "topK");
      moveField(root, "n", "candidateCount");
      moveField(root, "max_tokens", "maxOutputTokens");
      moveField(root, "max_completion_tokens", "maxOutputTokens");
      moveField(root, "stop", "stopSequences");
      return writeBody(root, body);
    }
    if (path.contains(":embedText")) {
      if (root.has("input")) {
        root.set("text", root.path("input").deepCopy());
        root.remove("input");
      }
      return writeBody(root, body);
    }
    return writeBody(root, body);
  }

  private static ArrayNode vertexTools(ObjectNode root) {
    ArrayNode tools = OBJECT_MAPPER.createArrayNode();
    JsonNode toolsNode = root.path("tools");
    if (!toolsNode.isArray()) {
      return tools;
    }
    ArrayNode functionDeclarations = OBJECT_MAPPER.createArrayNode();
    for (JsonNode tool : toolsNode) {
      JsonNode function = tool.path("function");
      if (!function.isObject()) {
        continue;
      }
      ObjectNode declaration = function.deepCopy();
      declaration.remove("strict");
      JsonNode parameters = declaration.path("parameters");
      if (parameters.isObject()) {
        recursivelyDeleteUnsupportedParameters((ObjectNode) parameters);
      }
      functionDeclarations.add(declaration);
    }
    if (!functionDeclarations.isEmpty()) {
      ObjectNode functions = OBJECT_MAPPER.createObjectNode();
      functions.set("functionDeclarations", functionDeclarations);
      tools.add(functions);
    }
    return tools;
  }

  private static ObjectNode vertexToolConfig(ObjectNode root) {
    JsonNode toolChoice = root.path("tool_choice");
    if (toolChoice.isMissingNode() || toolChoice.isNull()) {
      return null;
    }
    ObjectNode config = OBJECT_MAPPER.createObjectNode();
    ObjectNode functionCalling = OBJECT_MAPPER.createObjectNode();
    if (toolChoice.isObject() && toolChoice.path("function").has("name")) {
      functionCalling.put("mode", "ANY");
      ArrayNode allowed = OBJECT_MAPPER.createArrayNode();
      allowed.add(toolChoice.path("function").path("name").asText());
      functionCalling.set("allowed_function_names", allowed);
    } else if ("required".equals(toolChoice.asText())) {
      functionCalling.put("mode", "ANY");
    } else if ("none".equals(toolChoice.asText())) {
      functionCalling.put("mode", "NONE");
    } else {
      functionCalling.put("mode", "AUTO");
    }
    config.set("function_calling_config", functionCalling);
    return config;
  }

  private static void recursivelyDeleteUnsupportedParameters(ObjectNode object) {
    object.remove("additionalProperties");
    object.remove("additional_properties");
    object.remove("$schema");
    object.fields().forEachRemaining(entry -> {
      JsonNode value = entry.getValue();
      if (value instanceof ObjectNode child) {
        recursivelyDeleteUnsupportedParameters(child);
      } else if (value instanceof ArrayNode array) {
        for (JsonNode item : array) {
          if (item instanceof ObjectNode child) {
            recursivelyDeleteUnsupportedParameters(child);
          }
        }
      }
    });
  }

  private static String transformVertexPredictBody(ObjectNode root, String fallbackBody) {
    if (root.has("input")) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      ArrayNode instances = OBJECT_MAPPER.createArrayNode();
      JsonNode input = root.get("input");
      Iterable<JsonNode> items = input.isArray() ? input : List.of(input);
      for (JsonNode item : items) {
        ObjectNode instance = OBJECT_MAPPER.createObjectNode();
        if (item.isTextual()) {
          instance.put("content", item.asText());
          if (root.has("task_type")) {
            instance.set("task_type", root.path("task_type").deepCopy());
          }
        } else if (item.has("text")) {
          instance.set("text", item.path("text").deepCopy());
          if (item.path("image").has("base64") || item.path("image").has("url")) {
            ObjectNode image = OBJECT_MAPPER.createObjectNode();
            copyField((ObjectNode) item.path("image"), image, "url", "gcsUri");
            copyField((ObjectNode) item.path("image"), image, "base64", "bytesBase64Encoded");
            instance.set("image", image);
          }
        }
        instances.add(instance);
      }
      out.set("instances", instances);
      ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
      if (root.path("parameters").isObject()) {
        parameters.setAll((ObjectNode) root.path("parameters"));
      }
      if (root.has("dimensions")) {
        parameters.set("outputDimensionality", root.path("dimensions").deepCopy());
      }
      if (!parameters.isEmpty()) {
        out.set("parameters", parameters);
      }
      return writeBody(out, fallbackBody);
    }

    if (root.has("prompt")) {
      ObjectNode out = OBJECT_MAPPER.createObjectNode();
      ArrayNode instances = OBJECT_MAPPER.createArrayNode();
      JsonNode prompt = root.get("prompt");
      Iterable<JsonNode> prompts = prompt.isArray() ? prompt : List.of(prompt);
      for (JsonNode item : prompts) {
        ObjectNode instance = OBJECT_MAPPER.createObjectNode();
        instance.put("prompt", item.asText(""));
        instances.add(instance);
      }
      out.set("instances", instances);
      ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
      copyField(root, parameters, "n", "sampleCount");
      if (root.has("quality")) {
        ObjectNode outputOptions = OBJECT_MAPPER.createObjectNode();
        JsonNode quality = root.path("quality");
        outputOptions.put(
            "compressionQuality",
            quality.isNumber() ? quality.asInt() : ("hd".equals(quality.asText()) ? 100 : 75));
        parameters.set("outputOptions", outputOptions);
      }
      copyField(root, parameters, "style", "sampleImageStyle");
      copyField(root, parameters, "aspectRatio", "aspectRatio");
      copyField(root, parameters, "seed", "seed");
      copyField(root, parameters, "negativePrompt", "negativePrompt");
      copyField(root, parameters, "personGeneration", "personGeneration");
      copyField(root, parameters, "safetySetting", "safetySetting");
      copyField(root, parameters, "addWatermark", "addWatermark");
      if (!parameters.isEmpty()) {
        out.set("parameters", parameters);
      }
      return writeBody(out, fallbackBody);
    }
    root.remove("model");
    return writeBody(root, fallbackBody);
  }

  private static void addVertexParts(ArrayNode out, JsonNode content) {
    if (content == null || content.isMissingNode() || content.isNull()) {
      return;
    }
    if (content.isTextual()) {
      ObjectNode part = OBJECT_MAPPER.createObjectNode();
      part.put("text", content.asText());
      out.add(part);
      return;
    }
    if (!content.isArray()) {
      return;
    }
    for (JsonNode item : content) {
      if ("text".equals(item.path("type").asText())) {
        ObjectNode part = OBJECT_MAPPER.createObjectNode();
        part.put("text", item.path("text").asText(""));
        out.add(part);
      } else if ("image_url".equals(item.path("type").asText())) {
        String url = item.path("image_url").path("url").asText("");
        if (url.startsWith("data:") && url.contains(";base64,")) {
          String[] dataParts = url.split(";base64,", 2);
          ObjectNode inlineData = OBJECT_MAPPER.createObjectNode();
          inlineData.put("mimeType", dataParts[0].substring("data:".length()));
          inlineData.put("data", dataParts[1]);
          ObjectNode part = OBJECT_MAPPER.createObjectNode();
          part.set("inlineData", inlineData);
          out.add(part);
        } else if (url.startsWith("gs://") || url.startsWith("http://") || url.startsWith("https://")) {
          ObjectNode fileData = OBJECT_MAPPER.createObjectNode();
          fileData.put("mimeType", item.path("image_url").path("mime_type").asText("image/jpeg"));
          fileData.put("fileUri", url);
          ObjectNode part = OBJECT_MAPPER.createObjectNode();
          part.set("fileData", fileData);
          out.add(part);
        }
      }
    }
  }

  private static String transformCohereBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if ("/v2/embed".equals(path)) {
      ArrayNode texts = OBJECT_MAPPER.createArrayNode();
      ArrayNode images = OBJECT_MAPPER.createArrayNode();
      JsonNode input = root.get("input");
      if (input != null) {
        Iterable<JsonNode> items = input.isArray() ? input : List.of(input);
        for (JsonNode item : items) {
          if (item.isTextual()) {
            texts.add(item.asText());
          } else if (item.has("text")) {
            texts.add(item.path("text").asText(""));
          } else if (item.path("image").has("base64")) {
            images.add(item.path("image").path("base64").asText(""));
          }
        }
        root.remove("input");
      }
      if (!texts.isEmpty()) {
        root.set("texts", texts);
      }
      if (!images.isEmpty()) {
        root.set("images", images);
      }
      if (root.has("encoding_format")) {
        root.set("embedding_types", stringArray(root.get("encoding_format")));
        root.remove("encoding_format");
      }
    } else if ("/v2/chat".equals(path)) {
      convertDeveloperMessagesToSystem(root);
      if (root.has("stop")) {
        root.set("stop_sequences", stringArray(root.get("stop")));
        root.remove("stop");
      }
      moveField(root, "top_p", "p");
      if (root.path("response_format").path("type").asText().equals("json_schema")
          && root.path("response_format").path("json_schema").path("strict").asBoolean(false)) {
        root.put("strict_tools", true);
      }
      if ("required".equals(root.path("tool_choice").asText()) || root.path("tool_choice").isObject()) {
        root.put("tool_choice", "REQUIRED");
      } else if ("none".equals(root.path("tool_choice").asText())) {
        root.put("tool_choice", "NONE");
      } else if ("auto".equals(root.path("tool_choice").asText())) {
        root.remove("tool_choice");
      }
    } else if ("/v1/generate".equals(path)) {
      moveField(root, "top_p", "p");
      moveField(root, "top_k", "k");
      moveField(root, "n", "num_generations");
      if (root.has("stop")) {
        root.set("end_sequences", stringArray(root.get("stop")));
        root.remove("stop");
      }
    } else if ("/v1/embed-jobs".equals(path)) {
      moveField(root, "input_file_id", "dataset_id");
    }
    return writeBody(root, body);
  }

  private static String transformMistralBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if ("/chat/completions".equals(path) || "/fim/completions".equals(path)) {
      String model = root.path("model").asText(null);
      if (model != null) {
        root.put("model", model.replace("mistralai.", ""));
      }
      convertDeveloperMessagesToSystem(root);
      if ("required".equals(root.path("tool_choice").asText())) {
        root.put("tool_choice", "any");
      }
      moveField(root, "max_completion_tokens", "max_tokens");
      moveField(root, "seed", "random_seed");
      moveField(root, "safe_mode", "safe_prompt");
    }
    if ("/embeddings".equals(path)) {
      ensureInputArray(root);
    }
    return writeBody(root, body);
  }

  private static String transformStabilityBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (path.startsWith("/v2beta/stable-image/generate/")) {
      return stabilityMultipartBody(root);
    }
    if (!path.startsWith("/v1/generation/")) {
      return body;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    if (root.has("prompt")) {
      ArrayNode textPrompts = OBJECT_MAPPER.createArrayNode();
      ObjectNode prompt = OBJECT_MAPPER.createObjectNode();
      prompt.put("text", root.path("prompt").asText(""));
      prompt.put("weight", 1);
      textPrompts.add(prompt);
      out.set("text_prompts", textPrompts);
    }
    copyField(root, out, "n", "samples");
    copyField(root, out, "style", "style_preset");
    copyField(root, out, "cfg_scale", "cfg_scale");
    copyField(root, out, "clip_guidance_preset", "clip_guidance_preset");
    copyField(root, out, "sampler", "sampler");
    copyField(root, out, "seed", "seed");
    copyField(root, out, "steps", "steps");
    copyField(root, out, "extras", "extras");
    String size = root.path("size").asText("");
    if (size.contains("x")) {
      String[] parts = size.toLowerCase(Locale.ROOT).split("x", 2);
      out.put("width", Integer.parseInt(parts[0]));
      out.put("height", Integer.parseInt(parts[1]));
    }
    return writeBody(out, body);
  }

  private static String stabilityMultipartBody(ObjectNode root) {
    StringBuilder body = new StringBuilder();
    root.fields().forEachRemaining(entry -> {
      if ("model".equals(entry.getKey()) || entry.getValue().isNull()) {
        return;
      }
      String value = entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString();
      body.append("--").append(STABILITY_MULTIPART_BOUNDARY).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n")
          .append(value).append("\r\n");
    });
    body.append("--").append(STABILITY_MULTIPART_BOUNDARY).append("--\r\n");
    return body.toString();
  }

  private static String transformRecraftBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null || !"/images/generations".equals(path)) {
      return body;
    }
    if (!root.has("style")) {
      root.put("style", "realistic_image");
    }
    if (!root.has("n")) {
      root.put("n", 1);
    }
    if (!root.has("size")) {
      root.put("size", "1024x1024");
    }
    if (!root.has("response_format")) {
      root.put("response_format", "url");
    }
    return writeBody(root, body);
  }

  private static String transformAi21Body(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    root.remove("model");
    if (path.endsWith("/chat")) {
      JsonNode messages = root.path("messages");
      ArrayNode ai21Messages = OBJECT_MAPPER.createArrayNode();
      if (messages.isArray()) {
        int start = 0;
        if (!messages.isEmpty() && isSystemMessage(messages.get(0))) {
          root.set("system", messages.get(0).path("content").deepCopy());
          start = 1;
        }
        for (int i = start; i < messages.size(); i++) {
          JsonNode message = messages.get(i);
          ObjectNode ai21Message = ai21Messages.addObject();
          ai21Message.put("text", message.path("content").asText(""));
          ai21Message.put("role", message.path("role").asText(""));
        }
      }
      root.set("messages", ai21Messages);
      transformAi21CommonFields(root);
      return writeBody(root, body);
    }
    if (path.endsWith("/complete")) {
      transformAi21CommonFields(root);
      return writeBody(root, body);
    }
    if ("/embed".equals(path)) {
      if (root.has("input")) {
        root.set("texts", stringArray(root.path("input")));
        root.remove("input");
      }
      root.remove("model");
      return writeBody(root, body);
    }
    return writeBody(root, body);
  }

  private static void transformAi21CommonFields(ObjectNode root) {
    moveField(root, "n", "numResults");
    moveField(root, "max_tokens", "maxTokens");
    moveField(root, "max_completion_tokens", "maxTokens");
    moveField(root, "top_p", "topP");
    moveField(root, "top_k", "topKReturn");
    moveField(root, "stop", "stopSequences");
    if (root.has("presence_penalty")) {
      ObjectNode penalty = OBJECT_MAPPER.createObjectNode();
      penalty.set("scale", root.path("presence_penalty").deepCopy());
      root.set("presencePenalty", penalty);
      root.remove("presence_penalty");
    }
    if (root.has("frequency_penalty")) {
      ObjectNode penalty = OBJECT_MAPPER.createObjectNode();
      penalty.set("scale", root.path("frequency_penalty").deepCopy());
      root.set("frequencyPenalty", penalty);
      root.remove("frequency_penalty");
    }
  }

  private static boolean isSystemMessage(JsonNode message) {
    String role = message == null ? "" : message.path("role").asText("");
    return "system".equals(role) || "developer".equals(role);
  }

  private static String transformNomicEmbeddingBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (root.has("input")) {
      root.set("texts", stringArray(root.path("input")));
      root.remove("input");
    }
    return writeBody(root, body);
  }

  private static String transformPredibaseChatBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (root.has("model")) {
      root.put("model", afterColon(root.path("model").asText("")));
    }
    convertDeveloperMessagesToSystem(root);
    moveField(root, "max_completion_tokens", "max_tokens");
    return writeBody(root, body);
  }

  private static String transformRekaChatBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    moveField(root, "model", "model_name");
    ArrayNode conversationHistory = OBJECT_MAPPER.createArrayNode();
    String[] lastType = new String[] {null};
    JsonNode messages = root.path("messages");
    if (messages.isArray()) {
      for (JsonNode message : messages) {
        String type = "user".equals(message.path("role").asText("")) ? "human" : "model";
        JsonNode content = message.path("content");
        if (content.isArray()) {
          for (JsonNode item : content) {
            if ("image_url".equals(item.path("type").asText(""))) {
              addRekaMessage(
                  conversationHistory,
                  lastType,
                  type,
                  item.path("text").asText(""),
                  item.at("/image_url/url").asText(""));
            } else {
              addRekaMessage(conversationHistory, lastType, type, item.path("text").asText(""), "");
            }
          }
        } else {
          addRekaMessage(conversationHistory, lastType, type, content.asText(""), "");
        }
      }
    }
    if (conversationHistory.isEmpty() || !"human".equals(conversationHistory.get(0).path("type").asText())) {
      ObjectNode placeholder = OBJECT_MAPPER.createObjectNode();
      placeholder.put("type", "human");
      placeholder.put("text", "Placeholder for alternation");
      conversationHistory.insert(0, placeholder);
    }
    root.set("conversation_history", conversationHistory);
    root.remove("messages");
    moveField(root, "max_tokens", "request_output_len");
    moveField(root, "max_completion_tokens", "request_output_len");
    moveField(root, "top_p", "runtime_top_p");
    if (root.has("stop")) {
      root.set("stop_words", stringArray(root.path("stop")));
      root.remove("stop");
    }
    moveField(root, "seed", "random_seed");
    moveField(root, "top_k", "runtime_top_k");
    return writeBody(root, body);
  }

  private static void addRekaMessage(
      ArrayNode messages, String[] lastType, String type, String text, String mediaUrl) {
    if (hasText(mediaUrl) && !messages.isEmpty() && hasText(messages.get(0).path("media_url").asText(null))) {
      return;
    }
    ObjectNode message = OBJECT_MAPPER.createObjectNode();
    message.put("type", type);
    message.put("text", text);
    if (hasText(mediaUrl)) {
      message.put("media_url", mediaUrl);
    }
    if (type.equals(lastType[0])) {
      ObjectNode placeholder = OBJECT_MAPPER.createObjectNode();
      placeholder.put("type", "human".equals(type) ? "model" : "human");
      placeholder.put("text", "Placeholder for alternation");
      if (hasText(mediaUrl)) {
        messages.insert(0, placeholder);
      } else {
        messages.add(placeholder);
      }
    }
    if (hasText(mediaUrl)) {
      messages.insert(0, message);
    } else {
      messages.add(message);
    }
    lastType[0] = type;
  }

  private static String transformSegmindImageBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    root.remove("model");
    moveField(root, "n", "samples");
    String size = root.path("size").asText("");
    if (size.contains("x")) {
      String[] sizeParts = size.toLowerCase(Locale.ROOT).split("x", 2);
      root.put("img_width", Integer.parseInt(sizeParts[0]));
      root.put("img_height", Integer.parseInt(sizeParts[1]));
      root.remove("size");
    }
    putDefault(root, "style", "base");
    putDefault(root, "num_inference_steps", 20);
    putDefault(root, "negative_prompt", "out of frame, duplicate, watermark, signature, text, error, deformed");
    putDefault(root, "scheduler", "UniPC");
    putDefault(root, "guidance_scale", 7.5);
    putDefault(root, "strength", 0.75);
    putDefault(root, "refiner", true);
    putDefault(root, "high_noise_fraction", 0.8);
    putDefault(root, "base64", true);
    putDefault(root, "control_scale", 1.8);
    putDefault(root, "control_start", 0.19);
    putDefault(root, "control_end", 1);
    putDefault(root, "qr_text", "https://portkey.ai");
    putDefault(root, "invert", false);
    putDefault(root, "size", 768);
    return writeBody(root, body);
  }

  private static String transformBytezChatBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    ObjectNode params = root.path("params").isObject()
        ? root.path("params").deepCopy()
        : OBJECT_MAPPER.createObjectNode();
    if (root.has("max_tokens")) {
      params.set("max_new_tokens", root.path("max_tokens").deepCopy());
      root.remove("max_tokens");
    }
    if (root.has("temperature")) {
      params.set("temperature", root.path("temperature").deepCopy());
      root.remove("temperature");
    }
    if (root.has("top_p")) {
      params.set("top_p", root.path("top_p").deepCopy());
      root.remove("top_p");
    }
    root.set("params", params);
    root.remove("model");
    root.remove("version");
    return writeBody(root, body);
  }

  private static String transformOracleChatBody(String body, ProviderOptions providerOptions) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    ObjectNode out = OBJECT_MAPPER.createObjectNode();
    ObjectNode chatRequest = OBJECT_MAPPER.createObjectNode();
    chatRequest.put("apiFormat", root.path("api_format").asText("GENERIC"));
    ArrayNode messages = OBJECT_MAPPER.createArrayNode();
    JsonNode rootMessages = root.path("messages");
    if (rootMessages.isArray()) {
      for (JsonNode message : rootMessages) {
        ObjectNode oracleMessage = messages.addObject();
        oracleMessage.put("role", oracleRole(message.path("role").asText("")));
        ArrayNode content = oracleMessage.putArray("content");
        addOracleContent(content, message.path("content"));
      }
    }
    chatRequest.set("messages", messages);
    moveField(root, chatRequest, "frequency_penalty", "frequencyPenalty");
    moveField(root, chatRequest, "max_tokens", "maxTokens");
    moveField(root, chatRequest, "max_completion_tokens", "maxTokens");
    moveField(root, chatRequest, "n", "numGenerations");
    moveField(root, chatRequest, "temperature", "temperature");
    moveField(root, chatRequest, "top_p", "topP");
    moveField(root, chatRequest, "top_k", "topK");
    moveField(root, chatRequest, "presence_penalty", "presencePenalty");
    moveField(root, chatRequest, "seed", "seed");
    if (root.has("stream")) {
      chatRequest.set("isStream", root.path("stream").deepCopy());
    }
    if (root.has("stop")) {
      chatRequest.set("stop", stringArray(root.path("stop")));
    }
    out.set("chatRequest", chatRequest);
    out.put("compartmentId", firstText(
        root.path("compartment_id").asText(null),
        providerOptions.stringValue("oracleCompartmentId")));
    ObjectNode servingMode = out.putObject("servingMode");
    servingMode.put("servingType", firstText(
        root.path("serving_mode").asText(null),
        firstText(providerOptions.stringValue("oracleServingMode"), "ON_DEMAND")));
    servingMode.put("modelId", root.path("model").asText(""));
    return writeBody(out, body);
  }

  private static String transformTritonCompletionBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    moveField(root, "prompt", "text_input");
    moveField(root, "stop", "stop_words");
    root.remove("model");
    putDefault(root, "max_tokens", 100);
    putDefault(root, "temperature", 0.7);
    putDefault(root, "top_p", 0.7);
    putDefault(root, "top_k", 50);
    return writeBody(root, body);
  }

  private static String transformOllamaBody(String path, String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if ("/api/embeddings".equals(path)) {
      moveField(root, "input", "prompt");
      return writeBody(root, body);
    }
    if ("/v1/chat/completions".equals(path)) {
      convertDeveloperMessagesToSystem(root);
      moveField(root, "max_completion_tokens", "max_tokens");
      JsonNode thinking = root.path("thinking");
      if (thinking.isObject()) {
        root.put("think", !"disabled".equals(thinking.path("type").asText("")));
        root.remove("thinking");
      }
      return writeBody(root, body);
    }
    return body;
  }

  private static String transformTogetherAiBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    convertDeveloperMessagesToSystem(root);
    moveField(root, "max_completion_tokens", "max_tokens");
    moveField(root, "frequency_penalty", "repetition_penalty");
    return writeBody(root, body);
  }

  private static void addOracleContent(ArrayNode content, JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) {
      return;
    }
    if (value.isTextual()) {
      ObjectNode text = content.addObject();
      text.put("type", "TEXT");
      text.put("text", value.asText(""));
      return;
    }
    if (!value.isArray()) {
      return;
    }
    for (JsonNode item : value) {
      if (item.isTextual() || "text".equals(item.path("type").asText(""))) {
        ObjectNode text = content.addObject();
        text.put("type", "TEXT");
        text.put("text", item.isTextual() ? item.asText("") : item.path("text").asText(""));
      } else if ("image_url".equals(item.path("type").asText(""))) {
        ObjectNode image = content.addObject();
        image.put("type", "IMAGE");
        ObjectNode imageUrl = image.putObject("imageUrl");
        imageUrl.put("url", item.at("/image_url/url").asText(""));
        if (item.at("/image_url/detail").isTextual()) {
          imageUrl.put("detail", item.at("/image_url/detail").asText(""));
        }
      } else if ("input_audio".equals(item.path("type").asText(""))) {
        ObjectNode audio = content.addObject();
        audio.put("type", "AUDIO");
        audio.putObject("audioUrl").put("url", item.at("/input_audio/data").asText(""));
      }
    }
  }

  private static String oracleRole(String role) {
    return switch (role) {
      case "system", "developer" -> "SYSTEM";
      case "assistant" -> "ASSISTANT";
      case "tool", "function" -> "TOOL";
      default -> "USER";
    };
  }

  private static String transformOpenAiEdgeBody(String body, boolean developerToSystem, boolean openRouterUsage) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    if (developerToSystem) {
      convertDeveloperMessagesToSystem(root);
    }
    moveField(root, "max_completion_tokens", "max_tokens");
    if (openRouterUsage && root.has("stream_options")) {
      ObjectNode usage = root.path("usage").isObject()
          ? root.path("usage").deepCopy()
          : OBJECT_MAPPER.createObjectNode();
      if (root.path("stream_options").has("include_usage")) {
        usage.set("include", root.path("stream_options").path("include_usage").deepCopy());
      }
      root.set("usage", usage);
      root.remove("stream_options");
    }
    if (openRouterUsage && (root.path("reasoning").isObject() || hasText(root.path("reasoning_effort").asText(null)))) {
      ObjectNode reasoning = root.path("reasoning").isObject()
          ? root.path("reasoning").deepCopy()
          : OBJECT_MAPPER.createObjectNode();
      if (hasText(root.path("reasoning_effort").asText(null))) {
        reasoning.set("effort", root.path("reasoning_effort").deepCopy());
        root.remove("reasoning_effort");
      }
      root.set("reasoning", reasoning);
    }
    return writeBody(root, body);
  }

  private static String transformPerplexityBody(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    convertDeveloperMessagesToSystem(root);
    moveField(root, "max_completion_tokens", "max_tokens");
    moveField(root, "frequency_penalty", "repetition_penalty");
    return writeBody(root, body);
  }

  private static String transformEmbeddingInputToArray(String body) {
    ObjectNode root = parseObjectBody(body);
    if (root == null) {
      return body;
    }
    ensureInputArray(root);
    return writeBody(root, body);
  }

  private static void ensureInputArray(ObjectNode root) {
    JsonNode input = root.get("input");
    if (input != null && !input.isArray()) {
      ArrayNode values = OBJECT_MAPPER.createArrayNode();
      values.add(input.deepCopy());
      root.set("input", values);
    }
  }

  private static ArrayNode arrayFromInput(JsonNode input) {
    ArrayNode values = OBJECT_MAPPER.createArrayNode();
    if (input == null || input.isNull() || input.isMissingNode()) {
      return values;
    }
    if (input.isArray()) {
      input.forEach(item -> values.add(item.deepCopy()));
      return values;
    }
    values.add(input.deepCopy());
    return values;
  }

  private static ArrayNode stringArray(JsonNode value) {
    ArrayNode values = OBJECT_MAPPER.createArrayNode();
    if (value == null || value.isNull() || value.isMissingNode()) {
      return values;
    }
    if (value.isArray()) {
      value.forEach(item -> values.add(item.asText()));
      return values;
    }
    values.add(value.asText());
    return values;
  }

  private static void moveField(ObjectNode root, String source, String target) {
    JsonNode value = root.get(source);
    if (value != null && !value.isNull()) {
      root.set(target, value.deepCopy());
      root.remove(source);
    }
  }

  private static void moveField(ObjectNode sourceRoot, ObjectNode targetRoot, String source, String target) {
    JsonNode value = sourceRoot.get(source);
    if (value != null && !value.isNull()) {
      targetRoot.set(target, value.deepCopy());
      sourceRoot.remove(source);
    }
  }

  private static void copyField(ObjectNode source, ObjectNode target, String sourceName, String targetName) {
    JsonNode value = source.get(sourceName);
    if (value != null && !value.isNull()) {
      target.set(targetName, value.deepCopy());
    }
  }

  private static void putDefault(ObjectNode root, String fieldName, String value) {
    if (!root.has(fieldName) || root.path(fieldName).isNull()) {
      root.put(fieldName, value);
    }
  }

  private static void putDefault(ObjectNode root, String fieldName, int value) {
    if (!root.has(fieldName) || root.path(fieldName).isNull()) {
      root.put(fieldName, value);
    }
  }

  private static void putDefault(ObjectNode root, String fieldName, double value) {
    if (!root.has(fieldName) || root.path(fieldName).isNull()) {
      root.put(fieldName, value);
    }
  }

  private static void putDefault(ObjectNode root, String fieldName, boolean value) {
    if (!root.has(fieldName) || root.path(fieldName).isNull()) {
      root.put(fieldName, value);
    }
  }

  private static void clampNumber(ObjectNode root, String fieldName, Double min, Double max) {
    JsonNode value = root.get(fieldName);
    if (value == null || !value.isNumber()) {
      return;
    }
    double clamped = value.asDouble();
    if (min != null && clamped < min) {
      clamped = min;
    }
    if (max != null && clamped > max) {
      clamped = max;
    }
    if (value.isIntegralNumber() && Math.floor(clamped) == clamped) {
      root.put(fieldName, (long) clamped);
    } else {
      root.put(fieldName, clamped);
    }
  }

  private static void convertDeveloperMessagesToSystem(ObjectNode root) {
    JsonNode messages = root.path("messages");
    if (!messages.isArray()) {
      return;
    }
    for (JsonNode message : messages) {
      if (message instanceof ObjectNode objectMessage
          && "developer".equals(objectMessage.path("role").asText())) {
        objectMessage.put("role", "system");
      }
    }
  }

  private static ObjectNode parseObjectBody(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(body);
      return parsed instanceof ObjectNode objectNode ? objectNode : null;
    } catch (Exception exception) {
      return null;
    }
  }

  private static String writeBody(ObjectNode root, String fallbackBody) {
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (Exception exception) {
      return fallbackBody;
    }
  }

  private static boolean shouldStripOpenAiVersionPrefix(String provider, String baseUrl) {
    if (PROVIDERS_WITH_OPENAI_CLIENT_BASE_URL.contains(provider)) {
      return true;
    }
    String path = baseUrlPath(baseUrl);
    return path.endsWith("/v1") || path.endsWith("/openai/v1") || path.endsWith("/openai");
  }

  private static String baseUrlPath(String baseUrl) {
    try {
      String path = new URI(baseUrl).getPath();
      return path == null ? "" : path.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException exception) {
      return "";
    }
  }

  private static String joinUrl(String baseUrl, String endpointPath) {
    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    String normalizedPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
    return normalizedBaseUrl + normalizedPath;
  }

  private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    headers.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
    return Map.copyOf(normalized);
  }

  private static void addSafeProxyHeaders(Map<String, String> providerHeaders, Map<String, String> requestHeaders) {
    requestHeaders.forEach((key, value) -> {
      if (shouldForwardProxyHeader(key)) {
        if (isProviderCredentialHeader(key.toLowerCase(Locale.ROOT)) && hasProviderCredentialHeader(providerHeaders)) {
          return;
        }
        providerHeaders.put(key, value);
      }
    });
    addExplicitForwardHeaders(providerHeaders, requestHeaders, List.of());
  }

  private static void addExplicitForwardHeaders(
      Map<String, String> providerHeaders, Map<String, String> requestHeaders, List<String> configuredHeaders) {
    for (String headerName : requestedOrConfiguredForwardHeaders(requestHeaders, configuredHeaders)) {
      String normalizedHeaderName = headerName.trim().toLowerCase(Locale.ROOT);
      if (normalizedHeaderName.isEmpty() || !shouldForwardExplicitHeader(normalizedHeaderName)) {
        continue;
      }
      String value = requestHeaders.get(normalizedHeaderName);
      if (value != null) {
        if (isProviderCredentialHeader(normalizedHeaderName) && providerHeaders.containsKey(normalizedHeaderName)) {
          continue;
        }
        providerHeaders.put(normalizedHeaderName, value);
      }
    }
  }

  private static boolean isProviderCredentialHeader(String headerName) {
    return headerName.equals("authorization") || headerName.equals("api-key") || headerName.equals("x-api-key");
  }

  private static boolean hasProviderCredentialHeader(Map<String, String> providerHeaders) {
    return providerHeaders.keySet().stream()
        .map(name -> name.toLowerCase(Locale.ROOT))
        .anyMatch(ProviderRequestFactory::isProviderCredentialHeader);
  }

  private static List<String> requestedOrConfiguredForwardHeaders(
      Map<String, String> requestHeaders, List<String> configuredHeaders) {
    String requestForwardHeaders = GatewayHeaders.value(requestHeaders, "forward-headers");
    if (requestForwardHeaders != null) {
      return requestForwardHeaders.isBlank() ? List.of() : List.of(requestForwardHeaders.split(","));
    }
    return configuredHeaders == null ? List.of() : configuredHeaders;
  }

  private static List<String> forwardHeaders(Target target, GatewayConfig inheritedConfig) {
    if (target.forwardHeaders() != null) {
      return target.forwardHeaders();
    }
    return inheritedConfig == null ? List.of() : inheritedConfig.forwardHeaders();
  }

  private static boolean shouldForwardProxyHeader(String headerName) {
    String lowerCaseName = headerName.toLowerCase(Locale.ROOT);
    return !GatewayHeaders.isGatewayControlHeader(lowerCaseName)
        && isForwardableTransportHeader(lowerCaseName);
  }

  private static boolean shouldForwardExplicitHeader(String headerName) {
    return !GatewayHeaders.isForwardHeadersHeader(headerName)
        && isForwardableTransportHeader(headerName);
  }

  private static boolean isForwardableTransportHeader(String headerName) {
    return !headerName.equals("content-length")
        && !headerName.equals("expect")
        && !headerName.equals("host")
        && !headerName.equals("connection")
        && !headerName.equals("transfer-encoding")
        && !headerName.equals("upgrade");
  }

  private static boolean shouldForwardContentType(String method) {
    return method == null || !"GET".equals(method.toUpperCase(Locale.ROOT));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String firstText(String first, String second) {
    return hasText(first) ? first : second;
  }

  @SuppressWarnings("unchecked")
  private static String providerOptionValue(ProviderOptions providerOptions, String camelKey, String kebabKey) {
    String direct = firstText(providerOptions.stringValue(camelKey), providerOptions.stringValue(kebabKey));
    if (hasText(direct)) {
      return direct;
    }
    Object nested = providerOptions.get("providerOptions");
    if (nested instanceof Map<?, ?> nestedMap) {
      Object camelValue = nestedMap.get(camelKey);
      Object kebabValue = nestedMap.get(kebabKey);
      Object snakeValue = nestedMap.get(camelToSnake(camelKey));
      Object value = camelValue != null ? camelValue : (kebabValue != null ? kebabValue : snakeValue);
      return value == null ? null : String.valueOf(value);
    }
    return null;
  }

  private static String camelToSnake(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (char character : value.toCharArray()) {
      if (Character.isUpperCase(character)) {
        out.append('_').append(Character.toLowerCase(character));
      } else {
        out.append(character);
      }
    }
    return out.toString();
  }

  private static String beforeColon(String value) {
    int colon = value == null ? -1 : value.indexOf(':');
    return colon < 0 ? (value == null ? "" : value) : value.substring(0, colon);
  }

  private static String afterColon(String value) {
    int colon = value == null ? -1 : value.indexOf(':');
    return colon < 0 ? "" : value.substring(colon + 1);
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static String encodeQueryComponent(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  private static String appendQuery(String existingQuery, String queryParameter) {
    if (!hasText(queryParameter)) {
      return existingQuery;
    }
    if (!hasText(existingQuery)) {
      return queryParameter;
    }
    return existingQuery + "&" + queryParameter;
  }

  private static boolean booleanFromBody(String body, String fieldName) {
    ObjectNode root = parseObjectBody(body);
    return root != null && root.path(fieldName).asBoolean(false);
  }

  private static String[] vertexModelAndProvider(String model) {
    int separator = model.indexOf('.');
    if (separator > 0) {
      return new String[] {model.substring(0, separator), model.substring(separator + 1)};
    }
    return new String[] {"google", model};
  }

  private static String vertexProjectId(ProviderOptions providerOptions) {
    Object serviceAccount = providerOptions.get("vertexServiceAccountJson");
    if (serviceAccount instanceof Map<?, ?> map) {
      Object projectId = map.get("project_id");
      if (projectId != null && hasText(String.valueOf(projectId))) {
        return String.valueOf(projectId);
      }
    }
    return firstText(providerOptions.stringValue("vertexProjectId"), "");
  }

  private static String vertexPaginationQuery(String query) {
    Map<String, String> queryParams = parseQuery(query);
    String pageSize = firstText(queryParams.get("limit"), "20");
    String pageToken = firstText(queryParams.get("after"), "");
    return "pageSize=" + encodeQueryComponent(pageSize)
        + "&pageToken=" + encodeQueryComponent(pageToken);
  }

  private static String vertexStorageObjectPath(String fileId) {
    String decoded = URLDecoder.decode(fileId == null ? "" : fileId, StandardCharsets.UTF_8);
    if (decoded.startsWith("gs://")) {
      decoded = decoded.substring("gs://".length());
    }
    int slash = decoded.indexOf('/');
    if (slash < 0) {
      return "/" + encodePathSegment(decoded);
    }
    String bucket = decoded.substring(0, slash);
    String file = decoded.substring(slash + 1);
    String encodedFile = Arrays.stream(file.split("/"))
        .map(ProviderRequestFactory::encodePathSegment)
        .reduce((left, right) -> left + "/" + right)
        .orElse("");
    return "/" + encodePathSegment(bucket) + "/" + encodedFile;
  }

  private static String decodeComponent(String value) {
    if (!hasText(value)) {
      return "";
    }
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String containingFolder(String gcsUri) {
    if (!hasText(gcsUri) || !gcsUri.contains("/")) {
      return "";
    }
    return gcsUri.substring(0, gcsUri.lastIndexOf('/') + 1);
  }

  private static boolean isVertexStoragePath(String path) {
    if (path == null) {
      return false;
    }
    return "/v1/files".equals(path) || path.startsWith("/v1/files/");
  }

  private static String originOf(String url) {
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      String authority = uri.getRawAuthority();
      if (!hasText(scheme) || !hasText(authority)) {
        return trimTrailingSlash(url);
      }
      return scheme + "://" + authority;
    } catch (URISyntaxException exception) {
      return trimTrailingSlash(url);
    }
  }

  private static String trimTrailingSlash(String value) {
    String trimmed = value == null ? "" : value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static Map<String, String> parseQuery(String query) {
    if (!hasText(query)) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (String part : query.split("&")) {
      if (part.isBlank()) {
        continue;
      }
      int separator = part.indexOf('=');
      String key = separator < 0 ? part : part.substring(0, separator);
      String value = separator < 0 ? "" : part.substring(separator + 1);
      values.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return values;
  }

  private static EndpointParts splitEndpoint(String endpointPath) {
    int queryStart = endpointPath.indexOf('?');
    if (queryStart < 0) {
      return new EndpointParts(endpointPath, null);
    }
    return new EndpointParts(endpointPath.substring(0, queryStart), endpointPath.substring(queryStart + 1));
  }

  private record EndpointParts(String path, String query) {
    String withPath(String newPath) {
      return query == null ? newPath : newPath + "?" + query;
    }
  }

  private record CachedToken(String value, long expiresAtEpochSeconds) {}

  private record TokenResponse(String accessToken, long expiresInSeconds) {}

  @FunctionalInterface
  private interface TokenSupplier {
    TokenResponse get() throws Exception;
  }
}
