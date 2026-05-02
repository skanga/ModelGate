package com.modelgate.gateway.plugins;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultPluginRegistry implements PluginRegistry {
  private static final Pattern NAMED_GROUP_PATTERN = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>");
  private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```+(?:json)?\\s*([\\s\\S]*?)```+");
  private static final Pattern FENCED_CODE_PATTERN = Pattern.compile("```(\\w+)\\n[\\s\\S]*?\\n```");
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s,\"'{}\\[\\]]+");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final ConcurrentHashMap<String, CachedJwks> JWKS_CACHE = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Long> JWKS_SIGNATURE_REFRESHES = new ConcurrentHashMap<>();
  private static final String PROMPTFOO_BASE_URL = "https://api.promptfoo.dev/v1";
  private static final String QUALIFIRE_BASE_URL = "https://proxy.qualifire.ai/api/evaluation/evaluate";
  private static final String PILLAR_BASE_URL = "https://api.pillarseclabs.com/api/v1";
  private static final String PATRONUS_BASE_URL = "https://api.patronus.ai/v1/evaluate";
  private static final String APORIA_BASE_URL = "https://gr-prd.aporia.com";
  private static final String SYDEGUARD_URL = "https://guard.sydelabs.ai/api/v1/guard/generate-score";
  private static final String MISTRAL_BASE_URL = "https://api.mistral.ai";
  private static final String WALLED_AI_URL = "https://services.walled.ai/v1/walled-protect";
  private static final String LASSO_BASE_URL = "https://server.lasso.security";
  private static final String PANW_AIRS_URL = "https://service.api.aisecurity.paloaltonetworks.com/v1/scan/sync/request";
  private static final String EXA_SEARCH_URL = "https://api.exa.ai/search";
  private static final Map<String, String> LANGUAGE_ALIASES = Map.ofEntries(
      Map.entry("sql", "SQL"),
      Map.entry("py", "Python"),
      Map.entry("python", "Python"),
      Map.entry("gyp", "Python"),
      Map.entry("ts", "TypeScript"),
      Map.entry("tsx", "TypeScript"),
      Map.entry("typescript", "TypeScript"),
      Map.entry("js", "JavaScript"),
      Map.entry("jsx", "JavaScript"),
      Map.entry("javascript", "JavaScript"),
      Map.entry("java", "Java"),
      Map.entry("cs", "C#"),
      Map.entry("cpp", "C++"),
      Map.entry("c", "C"),
      Map.entry("rb", "Ruby"),
      Map.entry("php", "PHP"),
      Map.entry("swift", "Swift"),
      Map.entry("kt", "Kotlin"),
      Map.entry("go", "Go"),
      Map.entry("rs", "Rust"),
      Map.entry("scala", "Scala"),
      Map.entry("r", "R"),
      Map.entry("pl", "Perl"),
      Map.entry("sh", "Shell"),
      Map.entry("html", "HTML"),
      Map.entry("css", "CSS"),
      Map.entry("xml", "XML"),
      Map.entry("json", "JSON"),
      Map.entry("yml", "YAML"),
      Map.entry("yaml", "YAML"),
      Map.entry("md", "Markdown"),
      Map.entry("dockerfile", "Dockerfile"));

  private final Map<String, PluginHandler> handlers;

  private DefaultPluginRegistry(Map<String, PluginHandler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  public static DefaultPluginRegistry create() {
    return new DefaultPluginRegistry(Map.ofEntries(
        Map.entry("default.contains", DefaultPluginRegistry::contains),
        Map.entry("default.wordCount", DefaultPluginRegistry::wordCount),
        Map.entry("default.regexMatch", DefaultPluginRegistry::regexMatch),
        Map.entry("default.characterCount", DefaultPluginRegistry::characterCount),
        Map.entry("default.sentenceCount", DefaultPluginRegistry::sentenceCount),
        Map.entry("default.endsWith", DefaultPluginRegistry::endsWith),
        Map.entry("default.alluppercase", DefaultPluginRegistry::allUpperCase),
        Map.entry("default.alllowercase", DefaultPluginRegistry::allLowerCase),
        Map.entry("default.notNull", DefaultPluginRegistry::notNull),
        Map.entry("default.addPrefix", DefaultPluginRegistry::addPrefix),
        Map.entry("default.regexReplace", DefaultPluginRegistry::regexReplace),
        Map.entry("default.containsCode", DefaultPluginRegistry::containsCode),
        Map.entry("default.jsonKeys", DefaultPluginRegistry::jsonKeys),
        Map.entry("default.jsonSchema", DefaultPluginRegistry::jsonSchema),
        Map.entry("default.modelwhitelist", DefaultPluginRegistry::modelWhitelist),
        Map.entry("default.modelWhitelist", DefaultPluginRegistry::modelWhitelist),
        Map.entry("default.requiredMetadataKeys", DefaultPluginRegistry::requiredMetadataKeys),
        Map.entry("default.allowedRequestTypes", DefaultPluginRegistry::allowedRequestTypes),
        Map.entry("default.modelRules", DefaultPluginRegistry::modelRules),
        Map.entry("default.validUrls", DefaultPluginRegistry::validUrls),
        Map.entry("default.webhook", DefaultPluginRegistry::webhook),
        Map.entry("default.log", DefaultPluginRegistry::log),
        Map.entry("default.jwt", DefaultPluginRegistry::jwt),
        Map.entry("promptfoo.harm", DefaultPluginRegistry::promptfooHarm),
        Map.entry("promptfoo.guard", DefaultPluginRegistry::promptfooGuard),
        Map.entry("promptfoo.pii", DefaultPluginRegistry::promptfooPii),
        Map.entry("qualifire.contentModeration", (context, parameters) -> qualifire(context, parameters, "content_moderation_check")),
        Map.entry("qualifire.grounding", (context, parameters) -> qualifire(context, parameters, "grounding_check")),
        Map.entry("qualifire.policy", DefaultPluginRegistry::qualifirePolicy),
        Map.entry("qualifire.toolUseQuality", DefaultPluginRegistry::qualifireToolUseQuality),
        Map.entry("qualifire.hallucinations", (context, parameters) -> qualifire(context, parameters, "hallucinations_check")),
        Map.entry("qualifire.pii", (context, parameters) -> qualifire(context, parameters, "pii_check")),
        Map.entry("qualifire.promptInjections", DefaultPluginRegistry::qualifirePromptInjections),
        Map.entry("portkey.moderateContent", DefaultPluginRegistry::portkeyModerateContent),
        Map.entry("portkey.language", DefaultPluginRegistry::portkeyLanguage),
        Map.entry("portkey.pii", DefaultPluginRegistry::portkeyPii),
        Map.entry("portkey.gibberish", DefaultPluginRegistry::portkeyGibberish),
        Map.entry("aporia.validateProject", DefaultPluginRegistry::aporiaValidateProject),
        Map.entry("sydelabs.sydeguard", DefaultPluginRegistry::sydeguard),
        Map.entry("pillar.scanPrompt", (context, parameters) -> pillar(context, parameters, "scan/prompt")),
        Map.entry("pillar.scanResponse", (context, parameters) -> pillar(context, parameters, "scan/response")),
        Map.entry("patronus.phi", (context, parameters) -> patronusPiiLike(context, parameters, "phi")),
        Map.entry("patronus.pii", (context, parameters) -> patronusPiiLike(context, parameters, "pii")),
        Map.entry("patronus.isConcise", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:is-concise")),
        Map.entry("patronus.isHelpful", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:is-helpful")),
        Map.entry("patronus.isPolite", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:is-polite")),
        Map.entry("patronus.noApologies", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:no-apologies")),
        Map.entry("patronus.noGenderBias", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:no-gender-bias")),
        Map.entry("patronus.noRacialBias", (context, parameters) -> patronusJudge(context, parameters, "judge", "patronus:no-racial-bias")),
        Map.entry("patronus.retrievalAnswerRelevance", (context, parameters) -> patronusJudge(context, parameters, "answer-relevance", null)),
        Map.entry("patronus.retrievalHallucination", (context, parameters) -> patronusJudge(context, parameters, "hallucination", "patronus:hallucination")),
        Map.entry("patronus.toxicity", (context, parameters) -> patronusJudge(context, parameters, "toxicity", null)),
        Map.entry("patronus.custom", DefaultPluginRegistry::patronusCustom),
        Map.entry("mistral.moderateContent", DefaultPluginRegistry::mistralModerateContent),
        Map.entry("pangea.textGuard", DefaultPluginRegistry::pangeaTextGuard),
        Map.entry("pangea.pii", DefaultPluginRegistry::pangeaPii),
        Map.entry("bedrock.guard", DefaultPluginRegistry::bedrockGuard),
        Map.entry("acuvity.scan", DefaultPluginRegistry::acuvityScan),
        Map.entry("acuvity.Acuvity", DefaultPluginRegistry::acuvityScan),
        Map.entry("lasso.classify", DefaultPluginRegistry::lassoClassify),
        Map.entry("exa.online", DefaultPluginRegistry::exaOnline),
        Map.entry("azure.pii", DefaultPluginRegistry::azurePii),
        Map.entry("azure.contentSafety", DefaultPluginRegistry::azureContentSafety),
        Map.entry("azure.shieldPrompt", DefaultPluginRegistry::azureShieldPrompt),
        Map.entry("azure.protectedMaterial", DefaultPluginRegistry::azureProtectedMaterial),
        Map.entry("azure-ai.pii", DefaultPluginRegistry::azurePii),
        Map.entry("azure-ai.contentSafety", DefaultPluginRegistry::azureContentSafety),
        Map.entry("azure-ai.shieldPrompt", DefaultPluginRegistry::azureShieldPrompt),
        Map.entry("azure-ai.protectedMaterial", DefaultPluginRegistry::azureProtectedMaterial),
        Map.entry("promptsecurity.protectPrompt", (context, parameters) -> promptSecurity(context, parameters, "prompt")),
        Map.entry("promptsecurity.protectResponse", (context, parameters) -> promptSecurity(context, parameters, "response")),
        Map.entry("panw-prisma-airs.intercept", DefaultPluginRegistry::panwPrismaAirs),
        Map.entry("panwPrismaAirs.intercept", DefaultPluginRegistry::panwPrismaAirs),
        Map.entry("crowdstrike-aidr.guardChatCompletions", DefaultPluginRegistry::crowdstrikeAidrGuardChatCompletions),
        Map.entry("walledai.walledprotect", DefaultPluginRegistry::walledAi),
        Map.entry("javelin.guardrails", DefaultPluginRegistry::javelinGuardrails),
        Map.entry("f5-guardrails.scan", DefaultPluginRegistry::f5Guardrails)));
  }

  @Override
  public PluginResult execute(String pluginId, HookContext context, Map<String, ?> parameters) {
    PluginHandler handler = handlers.get(pluginId);
    if (handler == null) {
      throw new IllegalArgumentException("Unknown plugin id: " + pluginId);
    }
    return handler.execute(context, parameters == null ? Map.of() : JsonCopies.deepCopyMap(parameters));
  }

  private static PluginResult contains(HookContext context, Map<String, ?> parameters) {
    Object operatorValue = parameters.containsKey("operator") ? parameters.get("operator") : "any";
    String operator = String.valueOf(operatorValue).toLowerCase(Locale.ROOT);
    boolean caseSensitive = booleanParameter(parameters, "case_sensitive")
        || booleanParameter(parameters, "caseSensitive");
    String haystack = caseSensitive ? context.responseText() : context.responseText().toLowerCase(Locale.ROOT);
    List<String> words = stringList(parameters.get("words"));

    long matches = words.stream()
        .map(word -> caseSensitive ? word : word.toLowerCase(Locale.ROOT))
        .filter(haystack::contains)
        .count();

    return PluginResult.withVerdict(switch (operator) {
      case "all" -> matches == words.size();
      case "none" -> matches == 0;
      case "any" -> matches > 0;
      default -> throw new IllegalArgumentException("Unsupported contains operator: " + operator);
    });
  }

  private static PluginResult wordCount(HookContext context, Map<String, ?> parameters) {
    String text = context.responseText() == null ? "" : context.responseText().trim();
    int wordCount = text.isBlank() ? 0 : text.split("\\s+").length;
    Integer min = integerParameter(parameters.containsKey("minWords") ? parameters.get("minWords") : parameters.get("min"));
    Integer max = integerParameter(parameters.containsKey("maxWords") ? parameters.get("maxWords") : parameters.get("max"));
    boolean not = booleanParameter(parameters, "not");

    boolean inRange = (min == null || wordCount >= min) && (max == null || wordCount <= max);
    boolean verdict = not ? !inRange : inRange;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("word_count", wordCount);
    data.put("wordCount", wordCount);
    data.put("minWords", min);
    data.put("maxWords", max);
    data.put("not", not);
    data.put("verdict", verdict);
    data.put("explanation", min == null || max == null
        ? "The text contains %d words.".formatted(wordCount)
        : countExplanation("words", wordCount, min, max, not, verdict));
    data.put("textExcerpt", textExcerpt(text));
    return PluginResult.withData(verdict, data);
  }

  private static PluginResult addPrefix(HookContext context, Map<String, ?> parameters) {
    if ("afterRequestHook".equals(context.eventType())) {
      return PluginResult.pass();
    }
    Object prefixValue = parameters.get("prefix");
    if (!(prefixValue instanceof String prefix)) {
      return new PluginResult(true, "Prefix parameter is required and must be a string", Map.of(), false, null);
    }
    String requestType = context.requestType();
    if (requestType != null && !Set.of("chatComplete", "messages", "complete").contains(requestType)) {
      return PluginResult.pass();
    }
    String applyToRole = parameters.containsKey("applyToRole")
        ? String.valueOf(parameters.get("applyToRole"))
        : "user";
    boolean addToExisting = !parameters.containsKey("addToExisting")
        || booleanParameter(parameters, "addToExisting");
    boolean onlyIfEmpty = booleanParameter(parameters, "onlyIfEmpty");
    Map<String, Object> requestJson = JsonCopies.mutableDeepCopyMap(context.requestJson());
    if (requestJson.isEmpty()) {
      return new PluginResult(true, "Request JSON is empty or missing", Map.of(), false, null);
    }
    Object prompt = requestJson.get("prompt");
    if (prompt instanceof String promptText) {
      requestJson.put("prompt", prefix + promptText);
      return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
    }

    Object messagesValue = requestJson.get("messages");
    if (!(messagesValue instanceof List<?> messages)) {
      return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
    }

    List<Object> transformedMessages = new ArrayList<>(messages);
    for (int index = 0; index < transformedMessages.size(); index++) {
      Object message = transformedMessages.get(index);
      if (!(message instanceof Map<?, ?> messageMap)) {
        continue;
      }
      if (!applyToRole.equals(messageMap.get("role"))) {
        continue;
      }

      if (!addToExisting) {
        transformedMessages.add(index, newPrefixMessage(applyToRole, prefix, context.requestType()));
        requestJson.put("messages", transformedMessages);
        return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
      }
      if (onlyIfEmpty && !emptyMessageContent(messageMap.get("content"))) {
        requestJson.put("messages", transformedMessages);
        return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
      }
      Map<String, Object> transformedMessage = new LinkedHashMap<>();
      messageMap.forEach((key, value) -> transformedMessage.put(String.valueOf(key), JsonCopies.mutableDeepCopyValue(value)));
      transformedMessage.put("content", prefixedMessageContent(messageMap.get("content"), prefix));
      transformedMessages.set(index, transformedMessage);
      requestJson.put("messages", transformedMessages);
      return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
    }

    if ("system".equals(applyToRole)) {
      transformedMessages.addFirst(newPrefixMessage(applyToRole, prefix, context.requestType()));
    } else {
      transformedMessages.add(newPrefixMessage(applyToRole, prefix, context.requestType()));
    }
    requestJson.put("messages", transformedMessages);
    return prefixedTransform(requestJson, prefix, requestType, applyToRole, addToExisting, onlyIfEmpty);
  }

  private static PluginResult prefixedTransform(
      Map<String, ?> requestJson,
      String prefix,
      String requestType,
      String applyToRole,
      boolean addToExisting,
      boolean onlyIfEmpty) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("prefix", prefix);
    data.put("requestType", requestType);
    data.put("applyToRole", applyToRole);
    data.put("addToExisting", addToExisting);
    data.put("onlyIfEmpty", onlyIfEmpty);
    return new PluginResult(true, null, data, true, new TransformedData(JsonCopies.deepCopyMap(requestJson)));
  }

  private static Map<String, Object> newPrefixMessage(String role, String prefix, String requestType) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", role);
    message.put("content", "messages".equals(requestType) ? List.of(textContentPart(prefix)) : prefix);
    return message;
  }

  private static boolean emptyMessageContent(Object content) {
    if (content == null) {
      return true;
    }
    if (content instanceof String text) {
      return text.isEmpty();
    }
    if (content instanceof List<?> parts) {
      return parts.isEmpty();
    }
    return false;
  }

  private static Object prefixedMessageContent(Object content, String prefix) {
    if (content instanceof String text) {
      return prefix + text;
    }
    if (content instanceof List<?> parts) {
      List<Object> transformedParts = new ArrayList<>(parts);
      if (!transformedParts.isEmpty() && transformedParts.getFirst() instanceof Map<?, ?> firstPart) {
        Map<String, Object> transformedFirstPart = stringObjectMap(firstPart);
        if ("text".equals(transformedFirstPart.get("type")) && transformedFirstPart.get("text") instanceof String text) {
          transformedFirstPart.put("text", prefix + text);
          transformedParts.set(0, transformedFirstPart);
          return transformedParts;
        }
      }
      transformedParts.addFirst(textContentPart(prefix));
      return transformedParts;
    }
    return prefix + String.valueOf(content);
  }

  private static Map<String, Object> textContentPart(String text) {
    Map<String, Object> part = new LinkedHashMap<>();
    part.put("type", "text");
    part.put("text", text);
    return part;
  }

  private static PluginResult regexReplace(HookContext context, Map<String, ?> parameters) {
    String regexPattern = stringParameter(parameters.get("rule"));
    String redactText = parameters.containsKey("redactText")
        ? stringParameter(parameters.get("redactText"))
        : "[REDACTED]";
    boolean failOnDetection = booleanParameter(parameters, "failOnDetection");
    try {
      if (regexPattern == null || regexPattern.isEmpty()) {
        throw new IllegalArgumentException("Missing regex pattern");
      }
      String textToMatch = currentText(context);
      if (textToMatch == null || textToMatch.isEmpty()) {
        throw new IllegalArgumentException("Missing text to match");
      }

      Pattern pattern = regexReplacePattern(regexPattern);
      boolean hasMatches = pattern.matcher(textToMatch).find();
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("regexPattern", regexPattern);
      data.put("verdict", !(failOnDetection && hasMatches));
      data.put("explanation", hasMatches
          ? "Pattern '%s' matched and was replaced with '%s'".formatted(regexPattern, redactText)
          : "The regex pattern '%s' did not match any text.".formatted(regexPattern));
      if (!hasMatches) {
        return PluginResult.withData(true, data);
      }

      if ("afterRequestHook".equals(context.eventType()) && !context.responseJson().isEmpty()) {
        Map<String, Object> responseJson = replaceCurrentResponseText(context.responseJson(), pattern, redactText);
        return new PluginResult(!failOnDetection, null, data, true, TransformedData.forResponse(responseJson));
      }
      Map<String, Object> requestJson = JsonCopies.mutableDeepCopyMap(context.requestJson());
      if (!requestJson.isEmpty()) {
        replaceCurrentRequestText(requestJson, pattern, redactText);
        return new PluginResult(!failOnDetection, null, data, true, new TransformedData(requestJson));
      }
      return PluginResult.withData(!failOnDetection, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while processing the regex: " + exception.getMessage());
      data.put("regexPattern", regexPattern);
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static Pattern regexReplacePattern(String regexPattern) {
    if (!regexPattern.startsWith("/")) {
      return Pattern.compile(regexPattern);
    }
    int slashIndex = regexPattern.lastIndexOf('/');
    if (slashIndex <= 0) {
      return Pattern.compile(regexPattern);
    }
    String expression = regexPattern.substring(1, slashIndex);
    String flagsText = regexPattern.substring(slashIndex + 1);
    int flags = 0;
    for (int index = 0; index < flagsText.length(); index++) {
      flags |= switch (flagsText.charAt(index)) {
        case 'i' -> Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        case 'm' -> Pattern.MULTILINE;
        case 's' -> Pattern.DOTALL;
        case 'g', 'u', 'y', 'd' -> 0;
        default -> throw new IllegalArgumentException("Invalid regex flags: " + flagsText);
      };
    }
    return Pattern.compile(expression, flags);
  }

  private static void replaceCurrentRequestText(Map<String, Object> requestJson, Pattern pattern, String replacement) {
    Object prompt = requestJson.get("prompt");
    if (prompt instanceof String text) {
      requestJson.put("prompt", pattern.matcher(text).replaceAll(replacement));
      return;
    }
    Object input = requestJson.get("input");
    if (input instanceof String text) {
      requestJson.put("input", pattern.matcher(text).replaceAll(replacement));
      return;
    }
    if (input instanceof List<?> list) {
      requestJson.put("input", list.stream()
          .map(item -> item instanceof String text ? pattern.matcher(text).replaceAll(replacement) : item)
          .toList());
      return;
    }
    Object messagesValue = requestJson.get("messages");
    if (!(messagesValue instanceof List<?> messages) || messages.isEmpty()) {
      return;
    }
    List<Object> transformedMessages = new ArrayList<>(messages);
    Object last = transformedMessages.getLast();
    if (!(last instanceof Map<?, ?> messageMap)) {
      return;
    }
    Map<String, Object> message = new LinkedHashMap<>();
    messageMap.forEach((key, value) -> message.put(String.valueOf(key), JsonCopies.mutableDeepCopyValue(value)));
    Object content = message.get("content");
    if (content instanceof String text) {
      message.put("content", pattern.matcher(text).replaceAll(replacement));
    } else if (content instanceof List<?> parts) {
      message.put("content", parts.stream().map(part -> replaceTextPart(part, pattern, replacement)).toList());
    }
    transformedMessages.set(transformedMessages.size() - 1, message);
    requestJson.put("messages", transformedMessages);
  }

  private static Map<String, Object> replaceCurrentResponseText(Map<String, Object> responseJson, Pattern pattern, String replacement) {
    Map<String, Object> transformed = JsonCopies.mutableDeepCopyMap(responseJson);
    Object choicesValue = transformed.get("choices");
    if (choicesValue instanceof List<?> choices && !choices.isEmpty()) {
      List<Object> transformedChoices = new ArrayList<>(choices);
      Object firstChoice = transformedChoices.getFirst();
      if (firstChoice instanceof Map<?, ?> choiceMap) {
        Map<String, Object> choice = stringObjectMap(choiceMap);
        if (choice.get("text") instanceof String text) {
          choice.put("text", pattern.matcher(text).replaceAll(replacement));
          transformedChoices.set(0, choice);
          transformed.put("choices", transformedChoices);
          return transformed;
        }
        Map<String, Object> message = objectMap(choice.get("message"));
        Object content = message.get("content");
        if (content instanceof String text) {
          message.put("content", pattern.matcher(text).replaceAll(replacement));
        } else if (content instanceof List<?> parts) {
          message.put("content", parts.stream().map(part -> replaceTextPart(part, pattern, replacement)).toList());
        }
        choice.put("message", message);
        transformedChoices.set(0, choice);
        transformed.put("choices", transformedChoices);
        return transformed;
      }
    }
    Object content = transformed.get("content");
    if (content instanceof String text) {
      transformed.put("content", pattern.matcher(text).replaceAll(replacement));
    } else if (content instanceof List<?> parts) {
      transformed.put("content", parts.stream().map(part -> replaceTextPart(part, pattern, replacement)).toList());
    }
    return transformed;
  }

  private static Object replaceTextPart(Object part, Pattern pattern, String replacement) {
    if (!(part instanceof Map<?, ?> map)) {
      if (part instanceof String text) {
        return pattern.matcher(text).replaceAll(replacement);
      }
      return part;
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    map.forEach((key, value) -> copy.put(String.valueOf(key), JsonCopies.mutableDeepCopyValue(value)));
    Object text = copy.get("text");
    if (text instanceof String value) {
      copy.put("text", pattern.matcher(value).replaceAll(replacement));
    }
    return copy;
  }

  private static String currentText(HookContext context) {
    if (context.responseText() != null && !context.responseText().isBlank()) {
      return context.responseText();
    }
    return extractRequestText(context.requestJson());
  }

  private static String extractRequestText(Map<String, Object> requestJson) {
    Object prompt = requestJson.get("prompt");
    if (prompt instanceof String text) {
      return text;
    }
    Object input = requestJson.get("input");
    if (input instanceof String text) {
      return text;
    }
    if (input instanceof List<?> list) {
      return String.join("\n", list.stream().map(String::valueOf).toList());
    }
    Object messages = requestJson.get("messages");
    if (messages instanceof List<?> list && !list.isEmpty()) {
      Object last = list.getLast();
      if (last instanceof Map<?, ?> message) {
        Object content = message.get("content");
        if (content instanceof String text) {
          return text;
        }
        if (content instanceof List<?> parts) {
          return parts.stream()
              .map(DefaultPluginRegistry::textFromContentPart)
              .filter(text -> !text.isBlank())
              .reduce((left, right) -> left + "\n" + right)
              .orElse("");
        }
      }
    }
    return "";
  }

  private static String textFromContentPart(Object part) {
    if (part instanceof Map<?, ?> map) {
      Object text = map.get("text");
      return text == null ? "" : String.valueOf(text);
    }
    return "";
  }

  private static PluginResult containsCode(HookContext context, Map<String, ?> parameters) {
    String format = stringParameter(parameters.get("format"));
    boolean not = booleanParameter(parameters, "not");
    String text = context.responseText();
    try {
      if (format == null || format.isBlank()) {
        throw new IllegalArgumentException("Missing required parameter: format");
      }
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("No text content to analyze");
      }
      List<String> foundFormats = new ArrayList<>();
      Matcher matcher = FENCED_CODE_PATTERN.matcher(text);
      while (matcher.find()) {
        String language = matcher.group(1).toLowerCase(Locale.ROOT);
        foundFormats.add(LANGUAGE_ALIASES.getOrDefault(language, language));
      }
      boolean hasFormat = foundFormats.stream().anyMatch(format::equals);
      boolean verdict = not ? !hasFormat : hasFormat;
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", foundFormats.isEmpty()
          ? "No code blocks found in the text"
          : containsCodeExplanation(format, not, verdict));
      data.put("searchedFormat", format);
      data.put("not", not);
      data.put("foundFormats", foundFormats);
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "Error while checking for code blocks: " + exception.getMessage());
      data.put("searchedFormat", format);
      data.put("not", not);
      data.put("textExcerpt", text == null || text.isBlank() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static String containsCodeExplanation(String format, boolean not, boolean verdict) {
    if (verdict && not) {
      return "No code blocks in %s format found as expected".formatted(format);
    }
    if (verdict) {
      return "Found code block(s) in %s format".formatted(format);
    }
    if (not) {
      return "Found code block(s) in %s format when none were expected".formatted(format);
    }
    return "No code blocks in %s format found".formatted(format);
  }

  private static PluginResult jsonKeys(HookContext context, Map<String, ?> parameters) {
    List<String> keys = stringList(parameters.get("keys"));
    String operator = stringParameter(parameters.get("operator"));
    String text = context.responseText();
    try {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("Missing text to analyze");
      }
      if (keys.isEmpty()) {
        throw new IllegalArgumentException("Missing or invalid keys array");
      }
      if (operator == null || !Set.of("any", "all", "none").contains(operator)) {
        throw new IllegalArgumentException("Invalid or missing operator (must be \"any\", \"all\", or \"none\")");
      }
      List<Map<String, Object>> jsonObjects = extractedJsonObjects(text);
      if (jsonObjects.isEmpty()) {
        return PluginResult.withData(false, Map.of(
            "explanation", "No valid JSON found in the text.",
            "requiredKeys", keys,
            "operator", operator,
            "textExcerpt", textExcerpt(text)));
      }
      JsonKeyMatch bestMatch = new JsonKeyMatch(null, List.of(), keys, false);
      for (Map<String, Object> object : jsonObjects) {
        List<String> presentKeys = keys.stream().filter(object::containsKey).toList();
        List<String> missingKeys = keys.stream().filter(key -> !object.containsKey(key)).toList();
        boolean verdict = switch (operator) {
          case "any" -> !presentKeys.isEmpty();
          case "all" -> missingKeys.isEmpty();
          case "none" -> presentKeys.isEmpty();
          default -> false;
        };
        if (verdict || presentKeys.size() > bestMatch.presentKeys().size()) {
          bestMatch = new JsonKeyMatch(object, presentKeys, missingKeys, verdict);
        }
        if (verdict) {
          break;
        }
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("matchedJson", bestMatch.json());
      data.put("verdict", bestMatch.verdict());
      data.put("explanation", jsonKeysExplanation(operator, bestMatch.presentKeys(), bestMatch.missingKeys(), bestMatch.verdict()));
      data.put("presentKeys", bestMatch.presentKeys());
      data.put("missingKeys", bestMatch.missingKeys());
      data.put("operator", operator);
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(bestMatch.verdict(), data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while processing JSON: " + exception.getMessage());
      data.put("operator", operator);
      data.put("requiredKeys", keys);
      data.put("textExcerpt", text == null || text.isBlank() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private record JsonKeyMatch(Map<String, Object> json, List<String> presentKeys, List<String> missingKeys, boolean verdict) {}

  private static String jsonKeysExplanation(String operator, List<String> presentKeys, List<String> missingKeys, boolean verdict) {
    String presentKeysList = presentKeys.isEmpty()
        ? "No matching keys found"
        : "Found keys: [" + String.join(", ", presentKeys) + "]";
    String missingKeysList = missingKeys.isEmpty()
        ? "No missing keys"
        : "Missing keys: [" + String.join(", ", missingKeys) + "]";
    return switch (operator) {
      case "any" -> verdict
          ? "Successfully found at least one required key. " + presentKeysList + "."
          : "Failed to find any required keys. " + missingKeysList + ".";
      case "all" -> verdict
          ? "Successfully found all required keys. " + presentKeysList + "."
          : "Failed to find all required keys. " + missingKeysList + ".";
      case "none" -> verdict
          ? "Successfully verified no required keys are present. " + missingKeysList + "."
          : "Found some keys that should not be present. " + presentKeysList + ".";
      default -> "Invalid operator specified.";
    };
  }

  private static PluginResult jsonSchema(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> schema = objectMap(parameters.get("schema"));
    boolean not = booleanParameter(parameters, "not");
    try {
      if (schema.isEmpty()) {
        throw new IllegalArgumentException("Missing or invalid JSON schema");
      }
      List<Map<String, Object>> jsonObjects = extractedJsonObjects(context.responseText());
      if (jsonObjects.isEmpty()) {
        return PluginResult.withData(false, Map.of("explanation", "No valid JSON found in the response.", "not", not));
      }

      JsonSchemaMatch bestMatch = null;
      for (Map<String, Object> jsonObject : jsonObjects) {
        List<Map<String, Object>> errors = validateSchema(jsonObject, schema, "");
        JsonSchemaMatch match = new JsonSchemaMatch(jsonObject, errors, errors.isEmpty());
        if (bestMatch == null || match.valid()) {
          bestMatch = match;
        }
        if (match.valid()) {
          break;
        }
      }
      boolean schemaValid = bestMatch != null && bestMatch.valid();
      boolean verdict = not ? !schemaValid : schemaValid;
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("matchedJson", bestMatch == null ? null : bestMatch.json());
      data.put("not", not);
      data.put("explanation", jsonSchemaExplanation(not, verdict, schemaValid));
      data.put("validationErrors", bestMatch == null ? List.of() : bestMatch.errors());
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "An error occurred while processing the JSON.",
          "error", exception.getMessage(),
          "not", not), false, null);
    }
  }

  private record JsonSchemaMatch(Map<String, Object> json, List<Map<String, Object>> errors, boolean valid) {}

  private static String jsonSchemaExplanation(boolean not, boolean verdict, boolean schemaValid) {
    if (verdict && not) {
      return "Successfully validated JSON does not match the schema as expected.";
    }
    if (verdict) {
      return "Successfully validated JSON against the provided schema.";
    }
    if (not && schemaValid) {
      return "JSON matches the schema when it should not.";
    }
    return "Failed to validate JSON against the provided schema.";
  }

  private static List<Map<String, Object>> validateSchema(Object value, Map<String, Object> schema, String path) {
    List<Map<String, Object>> errors = new ArrayList<>();
    String type = stringParameter(schema.get("type"));
    if (type != null && !schemaTypeMatches(value, type)) {
      errors.add(schemaError(path, "Expected type " + type));
      return errors;
    }
    if ("object".equals(type) && value instanceof Map<?, ?> map) {
      for (String required : stringList(schema.get("required"))) {
        if (!map.containsKey(required)) {
          errors.add(schemaError(joinJsonPath(path, required), "Missing required property"));
        }
      }
      Map<String, Object> properties = objectMap(schema.get("properties"));
      for (Map.Entry<String, Object> property : properties.entrySet()) {
        if (map.containsKey(property.getKey()) && property.getValue() instanceof Map<?, ?> propertySchema) {
          errors.addAll(validateSchema(
              map.get(property.getKey()),
              stringObjectMap(propertySchema),
              joinJsonPath(path, property.getKey())));
        }
      }
    }
    return errors;
  }

  private static boolean schemaTypeMatches(Object value, String type) {
    return switch (type) {
      case "object" -> value instanceof Map<?, ?>;
      case "array" -> value instanceof List<?>;
      case "string" -> value instanceof String;
      case "integer" -> value instanceof Integer || value instanceof Long;
      case "number" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      default -> true;
    };
  }

  private static Map<String, Object> schemaError(String path, String message) {
    return Map.of("path", path, "message", message);
  }

  private static String joinJsonPath(String path, String property) {
    return path + "/" + property;
  }

  private static PluginResult modelWhitelist(HookContext context, Map<String, ?> parameters) {
    List<String> models = stringList(parameters.get("models"));
    boolean not = booleanParameter(parameters, "not");
    String requestModel = stringParameter(context.requestJson().get("model"));
    try {
      if (models.isEmpty()) {
        throw new IllegalArgumentException("Missing or invalid model whitelist");
      }
      if (requestModel == null || requestModel.isBlank()) {
        throw new IllegalArgumentException("Missing model in request");
      }
      boolean inList = models.contains(requestModel);
      boolean verdict = not ? !inList : inList;
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("verdict", verdict);
      data.put("not", not);
      data.put("explanation", modelWhitelistExplanation(requestModel, not, verdict));
      data.put("requestedModel", requestModel);
      data.put("allowedModels", models);
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "An error occurred while checking model whitelist: " + exception.getMessage(),
          "requestedModel", requestModel == null ? "No model specified" : requestModel,
          "not", not,
          "allowedModels", models), false, null);
    }
  }

  private static String modelWhitelistExplanation(String model, boolean not, boolean verdict) {
    if (verdict && not) {
      return "Model \"%s\" is not in the allowed list as expected.".formatted(model);
    }
    if (verdict) {
      return "Model \"%s\" is allowed.".formatted(model);
    }
    if (not) {
      return "Model \"%s\" is in the allowed list when it should not be.".formatted(model);
    }
    return "Model \"%s\" is not in the allowed list.".formatted(model);
  }

  private static PluginResult requiredMetadataKeys(HookContext context, Map<String, ?> parameters) {
    if ("afterRequestHook".equals(context.eventType())) {
      return new PluginResult(true, "This plugin only works for before_request_hooks", Map.of(), false, null);
    }
    Object metadataKeys = parameters.get("metadataKeys");
    if (!(metadataKeys instanceof List<?> rawKeys) || rawKeys.isEmpty()) {
      return new PluginResult(true, "metadataKeys must be an array and not empty", Map.of(), false, null);
    }
    if (rawKeys.stream().anyMatch(key -> !(key instanceof String))) {
      return new PluginResult(true, "metadataKeys must be an array of strings", Map.of(), false, null);
    }
    List<String> keys = rawKeys.stream().map(String.class::cast).toList();
    String operator = stringParameter(parameters.get("operator"));
    if (!Set.of("all", "any", "none").contains(operator)) {
      return new PluginResult(true, "operator must be one of: all, any, none", Map.of(), false, null);
    }
    List<String> found = keys.stream().filter(context.metadata()::containsKey).toList();
    List<String> missing = keys.stream().filter(key -> !context.metadata().containsKey(key)).toList();
    boolean verdict = switch (operator) {
      case "any" -> !found.isEmpty();
      case "all" -> missing.isEmpty();
      case "none" -> found.isEmpty();
      default -> false;
    };
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("explanation", "Check %s for '%s' metadata keys.".formatted(verdict ? "passed" : "failed", operator));
    data.put("missing_metadata_keys", missing);
    data.put("found_metadata_keys", found);
    data.put("operator", operator);
    return PluginResult.withData(verdict, data);
  }

  private static PluginResult allowedRequestTypes(HookContext context, Map<String, ?> parameters) {
    List<String> allowedTypes = List.of();
    List<String> blockedTypes = List.of();
    try {
      boolean parameterSource = (parameters.get("allowedTypes") != null && !stringListFlexible(parameters.get("allowedTypes")).isEmpty())
          || (parameters.get("blockedTypes") != null && !stringListFlexible(parameters.get("blockedTypes")).isEmpty());
      boolean metadataSource = context.metadata().get("supported_endpoints") != null || context.metadata().get("blocked_endpoints") != null;
      allowedTypes = stringListFlexible(parameters.get("allowedTypes"));
      blockedTypes = stringListFlexible(parameters.get("blockedTypes"));
      if (allowedTypes.isEmpty()) {
        allowedTypes = stringListFlexible(context.metadata().get("supported_endpoints"));
      }
      if (blockedTypes.isEmpty()) {
        blockedTypes = stringListFlexible(context.metadata().get("blocked_endpoints"));
      }
      String requestType = context.requestType();
      if (requestType == null || requestType.isBlank()) {
        throw new IllegalArgumentException("Request type not found in context");
      }
      List<String> conflicts = allowedTypes.stream().filter(blockedTypes::contains).toList();
      if (!conflicts.isEmpty()) {
        throw new IllegalArgumentException(
            "Conflict detected: The following types appear in both allowedTypes and blockedTypes: "
                + String.join(", ", conflicts)
                + ". Please remove them from one list.");
      }
      String mode;
      boolean verdict;
      if (allowedTypes.isEmpty() && blockedTypes.isEmpty()) {
        verdict = true;
        mode = "unrestricted";
      } else if (allowedTypes.isEmpty()) {
        verdict = !blockedTypes.contains(requestType);
        mode = "blocklist";
      } else if (blockedTypes.isEmpty()) {
        verdict = allowedTypes.contains(requestType);
        mode = "allowlist";
      } else {
        verdict = !blockedTypes.contains(requestType) && allowedTypes.contains(requestType);
        mode = "combined";
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("currentRequestType", requestType);
      data.put("allowedTypes", allowedTypes.isEmpty() && "unrestricted".equals(mode) ? List.of("all") : allowedTypes);
      if (!blockedTypes.isEmpty()) {
        data.put("blockedTypes", blockedTypes);
      }
      data.put("verdict", verdict);
      data.put("explanation", allowedRequestTypesExplanation(requestType, allowedTypes, blockedTypes, mode, verdict));
      data.put("source", parameterSource ? "parameters" : metadataSource ? "metadata" : "default");
      data.put("mode", mode);
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while checking allowed request types: " + exception.getMessage());
      data.put("currentRequestType", context.requestType() == null ? "unknown" : context.requestType());
      data.put("allowedTypes", allowedTypes);
      data.put("blockedTypes", blockedTypes);
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static String allowedRequestTypesExplanation(
      String requestType,
      List<String> allowedTypes,
      List<String> blockedTypes,
      String mode,
      boolean verdict) {
    return switch (mode) {
      case "blocklist" -> verdict
          ? "Request type \"%s\" is allowed (not in blocklist).".formatted(requestType)
          : "Request type \"%s\" is blocked.".formatted(requestType);
      case "allowlist" -> verdict
          ? "Request type \"%s\" is allowed.".formatted(requestType)
          : "Request type \"%s\" is not allowed. Allowed types are: %s".formatted(requestType, String.join(", ", allowedTypes));
      case "combined" -> verdict
          ? "Request type \"%s\" is allowed (in allowlist and not blocked).".formatted(requestType)
          : (blockedTypes.contains(requestType)
              ? "Request type \"%s\" is explicitly blocked.".formatted(requestType)
              : "Request type \"%s\" is not in the allowed list.".formatted(requestType));
      default -> "Request type \"%s\" is allowed (no restrictions configured).".formatted(requestType);
    };
  }

  private static PluginResult modelRules(HookContext context, Map<String, ?> parameters) {
    try {
      String requestModel = stringParameter(context.requestJson().get("model"));
      if (requestModel == null || requestModel.isBlank()) {
        throw new IllegalArgumentException("Missing model in request");
      }
      Map<String, Object> rules = objectMap(parameters.get("rules"));
      if (rules.isEmpty()) {
        throw new IllegalArgumentException("Missing rules configuration");
      }
      List<String> defaults = stringList(rules.get("defaults"));
      Map<String, Object> metadataRules = objectMap(rules.get("metadata"));
      List<String> matchedRules = new ArrayList<>();
      List<String> allowedModels = new ArrayList<>();
      for (Map.Entry<String, Object> metadataRule : metadataRules.entrySet()) {
        Object requestValue = context.metadata().get(metadataRule.getKey());
        if (requestValue == null) {
          continue;
        }
        Map<String, Object> valueRules = objectMap(metadataRule.getValue());
        for (String value : stringValues(requestValue)) {
          List<String> models = stringList(valueRules.get(value));
          if (!models.isEmpty()) {
            matchedRules.add(metadataRule.getKey() + ":" + value);
            for (String model : models) {
              if (!allowedModels.contains(model)) {
                allowedModels.add(model);
              }
            }
          }
        }
      }
      boolean usingDefaults = allowedModels.isEmpty();
      if (usingDefaults) {
        allowedModels = defaults;
      }
      if (allowedModels.isEmpty()) {
        throw new IllegalArgumentException("No allowed models resolved from rules");
      }
      boolean not = booleanParameter(parameters, "not");
      boolean inList = allowedModels.contains(requestModel);
      boolean verdict = not ? !inList : inList;
      String explanation = verdict
          ? (not
              ? "Model \"%s\" is not permitted by rules (blocked list).".formatted(requestModel)
              : "Model \"%s\" is allowed by rules.".formatted(requestModel))
          : (not
              ? "Model \"%s\" is permitted by rules (in blocked list).".formatted(requestModel)
              : "Model \"%s\" is not allowed by rules.".formatted(requestModel));
      if (verdict && !not && !matchedRules.isEmpty()) {
        explanation += " (matched rules: " + String.join(", ", matchedRules) + ")";
      } else if (verdict && !not && usingDefaults) {
        explanation += " (using default models)";
      }
      return PluginResult.withData(verdict, Map.of("explanation", explanation));
    } catch (IllegalArgumentException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "An error occurred while checking model rules: " + exception.getMessage()), false, null);
    }
  }

  private static PluginResult validUrls(HookContext context, Map<String, ?> parameters) {
    String text = currentText(context);
    boolean onlyDns = booleanParameter(parameters, "onlyDNS");
    boolean not = booleanParameter(parameters, "not");
    try {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("Missing text to analyze");
      }
      List<String> urls = urlsIn(text);
      if (urls.isEmpty()) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("explanation", "No URLs found in the text.");
        data.put("urls", List.of());
        data.put("validationMethod", onlyDns ? "DNS lookup" : "HTTP request");
        data.put("not", not);
        data.put("textExcerpt", textExcerpt(text));
        return PluginResult.withData(false, data);
      }
      List<String> validUrls = new ArrayList<>();
      List<String> invalidUrls = new ArrayList<>();
      for (String url : urls) {
        boolean valid = onlyDns ? dnsResolves(url) : headUrlOk(url);
        if (valid) {
          validUrls.add(url);
        } else {
          invalidUrls.add(url);
        }
      }
      boolean allValid = invalidUrls.isEmpty();
      boolean verdict = not ? !allValid : allValid;
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("verdict", verdict);
      data.put("not", not);
      data.put("explanation", validUrlsExplanation(not, verdict, validUrls.size(), invalidUrls.size(), urls.size()));
      data.put("validUrls", validUrls);
      data.put("invalidUrls", invalidUrls);
      data.put("validationMethod", onlyDns ? "DNS lookup" : "HTTP request");
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (RuntimeException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "An error occurred while validating URLs: " + exception.getMessage(),
          "validationMethod", onlyDns ? "DNS lookup" : "HTTP request",
          "not", not,
          "textExcerpt", text == null || text.isBlank() ? "No text available" : textExcerpt(text)), false, null);
    }
  }

  private static List<String> urlsIn(String text) {
    List<String> urls = new ArrayList<>();
    Matcher matcher = URL_PATTERN.matcher(text);
    while (matcher.find()) {
      urls.add(matcher.group());
    }
    return urls;
  }

  private static boolean dnsResolves(String target) {
    try {
      InetAddress.getByName(URI.create(target).getHost());
      return true;
    } catch (RuntimeException | java.io.IOException exception) {
      return false;
    }
  }

  private static boolean headUrlOk(String target) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(target))
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .timeout(Duration.ofSeconds(3))
          .header("User-Agent", "ModelGate-URLValidator/1.0")
          .build();
      int status = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status >= 200 && status < 400;
    } catch (RuntimeException | java.io.IOException exception) {
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String validUrlsExplanation(boolean not, boolean verdict, int validCount, int invalidCount, int totalCount) {
    if (verdict && not) {
      return "All URLs are invalid as expected (%d of %d).".formatted(invalidCount, totalCount);
    }
    if (verdict) {
      return "All URLs are valid (%d found).".formatted(validCount);
    }
    if (not) {
      return "Some URLs are valid when they should all be invalid (%d of %d).".formatted(validCount, totalCount);
    }
    return "Some URLs are invalid (%d of %d failed).".formatted(invalidCount, totalCount);
  }

  private static PluginResult webhook(HookContext context, Map<String, ?> parameters) {
    String webhookUrl = stringParameter(parameters.get("webhookURL"));
    try {
      if (webhookUrl == null || webhookUrl.isBlank()) {
        throw new IllegalArgumentException("Missing webhookURL");
      }
      Map<String, Object> response = postJson(webhookUrl, hookPayload(context), headerParameters(parameters));
      boolean verdict = response.get("verdict") instanceof Boolean bool && bool;
      Object dataValue = response.get("data");
      Map<String, Object> data = dataValue instanceof Map<?, ?> dataMap
          ? stringObjectMap(dataMap)
          : JsonCopies.deepCopyMap(response);
      TransformedData transformedData = transformedData(response.get("transformedData"));
      return new PluginResult(verdict, null, data, transformedData != null, transformedData);
    } catch (RuntimeException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "Webhook request failed: " + exception.getMessage()), false, null);
    }
  }

  private static PluginResult log(HookContext context, Map<String, ?> parameters) {
    String logUrl = stringParameter(parameters.get("logURL"));
    try {
      if (logUrl == null || logUrl.isBlank()) {
        throw new IllegalArgumentException("Missing logURL");
      }
      postJson(logUrl, hookPayload(context), headerParameters(parameters));
      return PluginResult.withData(true, Map.of("explanation", "Log request succeeded"));
    } catch (RuntimeException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(
          "explanation", "Log request failed: " + exception.getMessage()), false, null);
    }
  }

  private static PluginResult jwt(HookContext context, Map<String, ?> parameters) {
    String jwksUri = stringParameter(parameters.get("jwksUri"));
    String headerKey = stringParameter(parameters.get("headerKey"));
    if (headerKey == null || headerKey.isBlank()) {
      headerKey = "Authorization";
    }
    try {
      if (jwksUri == null || jwksUri.isBlank()) {
        throw new IllegalArgumentException("Missing JWKS URI");
      }
      String authorization = headerValue(context.headers(), headerKey);
      if (authorization == null || authorization.isBlank()) {
        return PluginResult.withData(false, Map.of(
            "verdict", false,
            "explanation", "Missing authorization header"));
      }
      String token = authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
      validateJwt(token, jwksUri, parameters);
      return PluginResult.withData(true, Map.of(
          "verdict", true,
          "explanation", "JWT token validation succeeded"));
    } catch (RuntimeException exception) {
      return PluginResult.withData(false, Map.of(
          "verdict", false,
          "explanation", "JWT validation error: " + exception.getMessage()));
    }
  }

  private static void validateJwt(String token, String jwksUri, Map<String, ?> parameters) {
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
      throw new IllegalArgumentException("Invalid JWT format");
    }
    Map<String, Object> header = decodeJwtPart(parts[0]);
    Map<String, Object> claims = decodeJwtPart(parts[1]);
    String alg = stringParameter(header.get("alg"));
    if (alg == null || !Set.of(
        "RS256", "RS384", "RS512",
        "PS256", "PS384", "PS512",
        "ES256", "ES384", "ES512").contains(alg)) {
      throw new IllegalArgumentException("Unsupported JWT algorithm: " + alg);
    }
    String kid = stringParameter(header.get("kid"));
    if (kid == null || kid.isBlank()) {
      throw new IllegalArgumentException("Missing kid in JWT header");
    }

    long cacheMaxAge = longParameter(parameters.get("cacheMaxAge"), 86400);
    Map<String, Object> jwk = jwkForKid(jwksUri, kid, cacheMaxAge, false);
    enforceJwkPolicy(jwk, alg);
    try {
      verifyJwtSignature(parts[0] + "." + parts[1], parts[2], alg, jwk);
    } catch (IllegalArgumentException exception) {
      if (!exception.getMessage().contains("Invalid JWT signature")) {
        throw exception;
      }
      String refreshKey = jwksSignatureRefreshKey(jwksUri, kid);
      if (!reserveJwksSignatureRefresh(refreshKey, cacheMaxAge)) {
        throw exception;
      }
      Map<String, Object> refreshedJwk = jwkForKid(jwksUri, kid, cacheMaxAge, true);
      enforceJwkPolicy(refreshedJwk, alg);
      verifyJwtSignature(parts[0] + "." + parts[1], parts[2], alg, refreshedJwk);
      JWKS_SIGNATURE_REFRESHES.remove(refreshKey);
    }
    validateJwtClaims(claims, parameters);
  }

  private static Map<String, Object> decodeJwtPart(String encoded) {
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      return OBJECT_MAPPER.readValue(decoded, MAP_TYPE);
    } catch (IllegalArgumentException | java.io.IOException exception) {
      throw new IllegalArgumentException("Invalid JWT JSON", exception);
    }
  }

  private static Map<String, Object> jwkForKid(String jwksUri, String kid, long cacheMaxAgeSeconds, boolean forceRefresh) {
    Map<String, Object> jwks = cachedJwks(jwksUri, cacheMaxAgeSeconds, forceRefresh);
    Map<String, Object> jwk = findJwk(jwks, kid);
    if (jwk == null && !forceRefresh) {
      jwks = cachedJwks(jwksUri, cacheMaxAgeSeconds, true);
      jwk = findJwk(jwks, kid);
    }
    if (jwk == null) {
      throw new IllegalArgumentException("No matching key found for kid");
    }
    return jwk;
  }

  private static void enforceJwkPolicy(Map<String, Object> jwk, String alg) {
    String jwkAlg = stringParameter(jwk.get("alg"));
    if (jwkAlg != null && !jwkAlg.isBlank() && !jwkAlg.equals(alg)) {
      throw new IllegalArgumentException("JWT alg %s does not match JWK alg %s".formatted(alg, jwkAlg));
    }
    String use = stringParameter(jwk.get("use"));
    if (use != null && !use.isBlank() && !"sig".equals(use)) {
      throw new IllegalArgumentException("JWK use must be sig");
    }
    List<String> keyOps = jwtClaimValues(jwk.get("key_ops"));
    if (!keyOps.isEmpty() && !keyOps.contains("verify")) {
      throw new IllegalArgumentException("JWK key_ops must include verify");
    }
    if (alg.startsWith("ES")) {
      String expectedCurve = switch (alg) {
        case "ES256" -> "P-256";
        case "ES384" -> "P-384";
        case "ES512" -> "P-521";
        default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + alg);
      };
      String actualCurve = stringParameter(jwk.get("crv"));
      if (!expectedCurve.equals(actualCurve)) {
        throw new IllegalArgumentException("JWT alg %s does not match JWK curve %s".formatted(alg, actualCurve));
      }
    }
  }

  private static String jwksSignatureRefreshKey(String jwksUri, String kid) {
    return jwksUri + "#" + kid;
  }

  private static boolean reserveJwksSignatureRefresh(String refreshKey, long cacheMaxAgeSeconds) {
    long nowMillis = System.currentTimeMillis();
    long ttlMillis = Math.max(1, cacheMaxAgeSeconds) * 1000;
    boolean[] reserved = new boolean[] {false};
    JWKS_SIGNATURE_REFRESHES.compute(refreshKey, (key, previous) -> {
      if (previous == null || previous <= nowMillis) {
        reserved[0] = true;
        return nowMillis + ttlMillis;
      }
      return previous;
    });
    return reserved[0];
  }

  private static Map<String, Object> cachedJwks(String jwksUri, long cacheMaxAgeSeconds, boolean forceRefresh) {
    long nowMillis = System.currentTimeMillis();
    String cacheKey = "jwks:" + jwksUri;
    CachedJwks cached = JWKS_CACHE.get(cacheKey);
    if (!forceRefresh && cached != null && cached.expiresAtMillis() > nowMillis) {
      return cached.jwks();
    }
    Map<String, Object> jwks = fetchJwks(jwksUri);
    JWKS_CACHE.put(cacheKey, new CachedJwks(jwks, nowMillis + Math.max(1, cacheMaxAgeSeconds) * 1000));
    return jwks;
  }

  private static Map<String, Object> fetchJwks(String jwksUri) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri))
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();
      HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalArgumentException("Failed to fetch JWKS from " + jwksUri);
      }
      return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("Failed to fetch JWKS from " + jwksUri, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Interrupted while fetching JWKS", exception);
    }
  }

  private static Map<String, Object> findJwk(Map<String, Object> jwks, String kid) {
    Object keysValue = jwks.get("keys");
    if (!(keysValue instanceof List<?> keys)) {
      return null;
    }
    for (Object key : keys) {
      if (key instanceof Map<?, ?> keyMap && kid.equals(keyMap.get("kid"))) {
        return stringObjectMap(keyMap);
      }
    }
    return null;
  }

  private static void verifyJwtSignature(String signingInput, String encodedSignature, String alg, Map<String, Object> jwk) {
    try {
      String keyType = stringParameter(jwk.get("kty"));
      PublicKey publicKey = publicKey(jwk, alg, keyType);
      Signature signature = Signature.getInstance(signatureAlgorithm(alg));
      if (isPssAlgorithm(alg)) {
        signature.setParameter(pssParameterSpec(alg));
      }
      signature.initVerify(publicKey);
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      byte[] rawSignature = Base64.getUrlDecoder().decode(encodedSignature);
      if (alg.startsWith("ES") && rawSignature.length != ecdsaJoseSignatureLength(alg)) {
        throw new IllegalArgumentException("Invalid ECDSA signature length");
      }
      byte[] signatureBytes = alg.startsWith("ES") ? joseToDer(rawSignature) : rawSignature;
      if (!signature.verify(signatureBytes)) {
        throw new IllegalArgumentException("Invalid JWT signature");
      }
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid JWT signature", exception);
    }
  }

  private static PublicKey publicKey(Map<String, Object> jwk, String alg, String keyType) throws Exception {
    if ((alg.startsWith("RS") || isPssAlgorithm(alg)) && "RSA".equals(keyType)) {
      return rsaPublicKey(jwk);
    }
    if (alg.startsWith("ES") && "EC".equals(keyType)) {
      return ecPublicKey(jwk);
    }
    throw new IllegalArgumentException("Unsupported JWK key type: " + keyType);
  }

  private static PublicKey rsaPublicKey(Map<String, Object> jwk) throws Exception {
    String modulus = stringParameter(jwk.get("n"));
    String exponent = stringParameter(jwk.get("e"));
    if (modulus == null || exponent == null) {
      throw new IllegalArgumentException("Invalid RSA JWK");
    }
    BigInteger modulusValue = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
    if (modulusValue.bitLength() < 2048) {
      throw new IllegalArgumentException("RSA JWK modulus must be at least 2048 bits");
    }
    RSAPublicKeySpec spec = new RSAPublicKeySpec(
        modulusValue,
        new BigInteger(1, Base64.getUrlDecoder().decode(exponent)));
    return KeyFactory.getInstance("RSA").generatePublic(spec);
  }

  private static PublicKey ecPublicKey(Map<String, Object> jwk) throws Exception {
    String curve = stringParameter(jwk.get("crv"));
    String x = stringParameter(jwk.get("x"));
    String y = stringParameter(jwk.get("y"));
    if (curve == null || x == null || y == null) {
      throw new IllegalArgumentException("Invalid EC JWK");
    }
    AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec(ecCurveName(curve)));
    ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
    ECPoint point = new ECPoint(
        new BigInteger(1, Base64.getUrlDecoder().decode(x)),
        new BigInteger(1, Base64.getUrlDecoder().decode(y)));
    return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
  }

  private static String ecCurveName(String curve) {
    return switch (curve) {
      case "P-256" -> "secp256r1";
      case "P-384" -> "secp384r1";
      case "P-521" -> "secp521r1";
      default -> throw new IllegalArgumentException("Unsupported EC curve: " + curve);
    };
  }

  private static String signatureAlgorithm(String alg) {
    return switch (alg) {
      case "RS256" -> "SHA256withRSA";
      case "RS384" -> "SHA384withRSA";
      case "RS512" -> "SHA512withRSA";
      case "PS256", "PS384", "PS512" -> "RSASSA-PSS";
      case "ES256" -> "SHA256withECDSA";
      case "ES384" -> "SHA384withECDSA";
      case "ES512" -> "SHA512withECDSA";
      default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + alg);
    };
  }

  private static boolean isPssAlgorithm(String alg) {
    return alg != null && alg.startsWith("PS");
  }

  private static PSSParameterSpec pssParameterSpec(String alg) {
    return switch (alg) {
      case "PS256" -> new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
      case "PS384" -> new PSSParameterSpec("SHA-384", "MGF1", MGF1ParameterSpec.SHA384, 48, 1);
      case "PS512" -> new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1);
      default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + alg);
    };
  }

  private static int ecdsaJoseSignatureLength(String alg) {
    return switch (alg) {
      case "ES256" -> 64;
      case "ES384" -> 96;
      case "ES512" -> 132;
      default -> throw new IllegalArgumentException("Unsupported JWT algorithm: " + alg);
    };
  }

  private static byte[] joseToDer(byte[] joseSignature) {
    if (joseSignature.length % 2 != 0 || joseSignature.length == 0) {
      throw new IllegalArgumentException("Invalid ECDSA signature");
    }
    int partLength = joseSignature.length / 2;
    byte[] r = derInteger(java.util.Arrays.copyOfRange(joseSignature, 0, partLength));
    byte[] s = derInteger(java.util.Arrays.copyOfRange(joseSignature, partLength, joseSignature.length));
    int sequenceLength = 2 + r.length + 2 + s.length;
    int headerLength = sequenceLength > 127 ? 3 : 2;
    byte[] der = new byte[headerLength + sequenceLength];
    int offset = 0;
    der[offset++] = 0x30;
    if (sequenceLength > 127) {
      der[offset++] = (byte) 0x81;
      der[offset++] = (byte) sequenceLength;
    } else {
      der[offset++] = (byte) sequenceLength;
    }
    der[offset++] = 0x02;
    der[offset++] = (byte) r.length;
    System.arraycopy(r, 0, der, offset, r.length);
    offset += r.length;
    der[offset++] = 0x02;
    der[offset++] = (byte) s.length;
    System.arraycopy(s, 0, der, offset, s.length);
    return der;
  }

  private static byte[] derInteger(byte[] value) {
    int firstNonZero = 0;
    while (firstNonZero < value.length - 1 && value[firstNonZero] == 0) {
      firstNonZero++;
    }
    byte[] unsigned = java.util.Arrays.copyOfRange(value, firstNonZero, value.length);
    if ((unsigned[0] & 0x80) == 0) {
      return unsigned;
    }
    byte[] positive = new byte[unsigned.length + 1];
    System.arraycopy(unsigned, 0, positive, 1, unsigned.length);
    return positive;
  }

  private static void validateJwtClaims(Map<String, Object> claims, Map<String, ?> parameters) {
    long now = Instant.now().getEpochSecond();
    long tolerance = longParameter(parameters.get("clockTolerance"), 5);
    Long expiresAt = longClaim(claims.get("exp"));
    if (expiresAt != null && now - tolerance > expiresAt) {
      throw new IllegalArgumentException("JWT token expired");
    }
    Long notBefore = longClaim(claims.get("nbf"));
    if (notBefore != null && now + tolerance < notBefore) {
      throw new IllegalArgumentException("JWT token is not active yet");
    }
    Long issuedAt = longClaim(claims.get("iat"));
    long maxTokenAge = maxTokenAgeSeconds(parameters.get("maxTokenAge"), 86400);
    if (issuedAt == null) {
      throw new IllegalArgumentException("Missing iat claim required for maxTokenAge validation");
    }
    if (issuedAt > now + tolerance) {
      throw new IllegalArgumentException("JWT token was issued in the future");
    }
    if (issuedAt != null && now - tolerance > issuedAt + maxTokenAge) {
      throw new IllegalArgumentException("JWT token is too old");
    }
    List<String> expectedIssuers = stringListFlexible(parameters.get("issuer"));
    if (!expectedIssuers.isEmpty()) {
      String issuer = stringParameter(claims.get("iss"));
      if (issuer == null || !expectedIssuers.contains(issuer)) {
        throw new IllegalArgumentException("JWT issuer mismatch");
      }
    }
    List<String> expectedAudiences = stringListFlexible(parameters.get("audience"));
    if (!expectedAudiences.isEmpty()) {
      List<String> tokenAudiences = jwtClaimValues(claims.get("aud"));
      if (tokenAudiences.stream().noneMatch(expectedAudiences::contains)) {
        throw new IllegalArgumentException("JWT audience mismatch");
      }
    }
  }

  private static List<String> jwtClaimValues(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(String::valueOf).toList();
    }
    return List.of(String.valueOf(value));
  }

  private static Long longClaim(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid numeric JWT claim", exception);
    }
  }

  private static long maxTokenAgeSeconds(Object value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (text.isBlank()) {
      return defaultValue;
    }
    long multiplier = switch (text.charAt(text.length() - 1)) {
      case 'd' -> 86400L;
      case 'h' -> 3600L;
      case 'm' -> 60L;
      case 's' -> 1L;
      default -> 1L;
    };
    String numberText = Character.isLetter(text.charAt(text.length() - 1))
        ? text.substring(0, text.length() - 1)
        : text;
    return Long.parseLong(numberText) * multiplier;
  }

  private record CachedJwks(Map<String, Object> jwks, long expiresAtMillis) {
    private CachedJwks {
      jwks = JsonCopies.deepCopyMap(jwks);
    }
  }

  private static PluginResult promptfooHarm(HookContext context, Map<String, ?> parameters) {
    try {
      Map<String, Object> result = postPromptfoo(parameters, "harm", Map.of("input", currentText(context)));
      Map<String, Object> firstResult = firstPromptfooResult(result);
      return PluginResult.withData(!booleanValue(firstResult.get("flagged")), firstResult);
    } catch (RuntimeException exception) {
      return new PluginResult(true, exception.getMessage(), Map.of(), false, null);
    }
  }

  private static PluginResult promptfooGuard(HookContext context, Map<String, ?> parameters) {
    try {
      Map<String, Object> result = postPromptfoo(parameters, "guard", Map.of("input", currentText(context)));
      Map<String, Object> firstResult = firstPromptfooResult(result);
      Map<String, Object> categories = objectMap(firstResult.get("categories"));
      return PluginResult.withData(!booleanValue(categories.get("jailbreak")), firstResult);
    } catch (RuntimeException exception) {
      return new PluginResult(true, exception.getMessage(), Map.of(), false, null);
    }
  }

  private static PluginResult promptfooPii(HookContext context, Map<String, ?> parameters) {
    try {
      boolean redact = booleanParameter(parameters, "redact");
      if ("embed".equals(context.requestType()) && redact) {
        return new PluginResult(true, "PII redaction is not supported for embed requests", Map.of(), false, null);
      }
      String text = currentText(context);
      if (text == null || text.isBlank()) {
        return new PluginResult(true, "request or response json is empty", Map.of(), false, null);
      }
      Map<String, Object> result = postPromptfoo(parameters, "pii", Map.of("input", text));
      Map<String, Object> firstResult = firstPromptfooResult(result);
      boolean flagged = booleanValue(firstResult.get("flagged"));
      if (flagged && redact) {
        String maskedText = maskPromptfooPii(text, firstResult);
        return new PluginResult(true, null, firstResult, true, transformedCurrentText(context, maskedText));
      }
      return PluginResult.withData(!flagged, firstResult);
    } catch (RuntimeException exception) {
      return new PluginResult(true, exception.getMessage(), Map.of(), false, null);
    }
  }

  private static Map<String, Object> postPromptfoo(Map<String, ?> parameters, String endpoint, Map<String, Object> payload) {
    String baseUrl = stringParameter(parameters.get("baseUrl"));
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = PROMPTFOO_BASE_URL;
    }
    return postJson(baseUrl.replaceAll("/+$", "") + "/" + endpoint, payload, Map.of());
  }

  private static Map<String, Object> firstPromptfooResult(Map<String, Object> response) {
    Object resultsValue = response.get("results");
    if (resultsValue instanceof List<?> results && !results.isEmpty() && results.getFirst() instanceof Map<?, ?> first) {
      return stringObjectMap(first);
    }
    return Map.of();
  }

  private static String maskPromptfooPii(String text, Map<String, Object> piiResult) {
    Map<String, Object> payload = objectMap(piiResult.get("payload"));
    Object piiValue = payload.get("pii");
    if (!(piiValue instanceof List<?> piiEntries)) {
      return text;
    }
    List<Map<String, Object>> entries = piiEntries.stream()
        .filter(Map.class::isInstance)
        .map(entry -> stringObjectMap((Map<?, ?>) entry))
        .sorted((left, right) -> Long.compare(longParameter(right.get("start"), 0), longParameter(left.get("start"), 0)))
        .toList();
    String maskedText = text;
    for (Map<String, Object> entry : entries) {
      int start = Math.max(0, Math.min(maskedText.length(), (int) longParameter(entry.get("start"), 0)));
      int end = Math.max(start, Math.min(maskedText.length(), (int) longParameter(entry.get("end"), start)));
      String entityType = stringParameter(entry.get("entity_type"));
      String maskText = "[" + (entityType == null ? "PII" : entityType.toUpperCase(Locale.ROOT)) + "]";
      maskedText = maskedText.substring(0, start) + maskText + maskedText.substring(end);
    }
    return maskedText;
  }

  private static Map<String, Object> setCurrentRequestText(Map<String, Object> requestJson, String text) {
    Map<String, Object> transformed = JsonCopies.mutableDeepCopyMap(requestJson);
    if (transformed.get("prompt") instanceof String) {
      transformed.put("prompt", text);
      return transformed;
    }
    if (transformed.get("input") instanceof String) {
      transformed.put("input", text);
      return transformed;
    }
    Object messagesValue = transformed.get("messages");
    if (messagesValue instanceof List<?> messages && !messages.isEmpty()) {
      List<Object> transformedMessages = new ArrayList<>(messages);
      Object last = transformedMessages.getLast();
      if (last instanceof Map<?, ?> messageMap) {
        Map<String, Object> message = stringObjectMap(messageMap);
        if (message.get("content") instanceof String) {
          message.put("content", text);
          transformedMessages.set(transformedMessages.size() - 1, message);
          transformed.put("messages", transformedMessages);
        }
      }
    }
    return transformed;
  }

  private static TransformedData transformedCurrentText(HookContext context, String text) {
    if (!context.responseText().isBlank() && !context.responseJson().isEmpty()) {
      return TransformedData.forResponse(setCurrentResponseText(context.responseJson(), text));
    }
    return new TransformedData(setCurrentRequestText(context.requestJson(), text));
  }

  private static Map<String, Object> setCurrentResponseText(Map<String, Object> responseJson, String text) {
    Map<String, Object> transformed = JsonCopies.mutableDeepCopyMap(responseJson);
    Object choicesValue = transformed.get("choices");
    if (choicesValue instanceof List<?> choices && !choices.isEmpty()) {
      List<Object> transformedChoices = new ArrayList<>(choices);
      Object firstChoice = transformedChoices.getFirst();
      if (firstChoice instanceof Map<?, ?> choiceMap) {
        Map<String, Object> choice = stringObjectMap(choiceMap);
        if (choice.get("text") instanceof String) {
          choice.put("text", text);
          transformedChoices.set(0, choice);
          transformed.put("choices", transformedChoices);
          return transformed;
        }
        Map<String, Object> message = objectMap(choice.get("message"));
        if (!message.isEmpty()) {
          message.put("content", text);
          choice.put("message", message);
          transformedChoices.set(0, choice);
          transformed.put("choices", transformedChoices);
        }
      }
    }
    return transformed;
  }

  private static boolean booleanValue(Object value) {
    return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
  }

  private static PluginResult qualifire(HookContext context, Map<String, ?> parameters, String checkKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("input", extractRequestText(context.requestJson()));
    if (!context.responseText().isBlank()) {
      body.put("output", context.responseText());
    }
    body.put(checkKey, true);
    if (checkKey.endsWith("_check")) {
      String modeKey = checkKey.substring(0, checkKey.length() - "_check".length()) + "_mode";
      Object mode = parameters.get("mode");
      if (mode != null) {
        body.put(modeKey, mode);
      }
    }
    return postQualifire(parameters, body);
  }

  private static PluginResult qualifirePolicy(HookContext context, Map<String, ?> parameters) {
    if (!parameters.containsKey("policies")) {
      return new PluginResult(true, "Qualifire Policy guardrail requires policies to be provided.", Map.of(), false, null);
    }
    String policyTarget = parameterOrDefault(parameters, "policy_target", "both").toString();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assertions", JsonCopies.mutableDeepCopyValue(parameters.get("policies")));
    body.put("assertions_mode", parameterOrDefault(parameters, "mode", "balanced"));
    if ("input".equals(policyTarget) || "both".equals(policyTarget)) {
      body.put("input", extractRequestText(context.requestJson()));
    }
    if (!context.responseText().isBlank() && ("output".equals(policyTarget) || "both".equals(policyTarget))) {
      body.put("output", context.responseText());
    }
    return postQualifire(parameters, body);
  }

  private static PluginResult qualifirePromptInjections(HookContext context, Map<String, ?> parameters) {
    if (!context.responseText().isBlank()) {
      return new PluginResult(false, "Qualifire Prompt Injections guardrail only supports before_request_hooks.", Map.of(), false, null);
    }
    return qualifire(context, parameters, "prompt_injections");
  }

  private static PluginResult qualifireToolUseQuality(HookContext context, Map<String, ?> parameters) {
    if (context.responseText().isBlank()) {
      return new PluginResult(true, "Qualifire Tool Use Quality guardrail only supports after_request_hooks.", Map.of(), false, null);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messages", qualifireMessages(context));
    Object tools = qualifireAvailableTools(context.requestJson());
    if (tools != null) {
      body.put("available_tools", tools);
    }
    body.put("tool_selection_quality_check", true);
    body.put("tsq_mode", parameterOrDefault(parameters, "mode", "balanced"));
    return postQualifire(parameters, body);
  }

  private static PluginResult postQualifire(Map<String, ?> parameters, Map<String, Object> body) {
    try {
      String apiKey = stringParameter(credentials(parameters).get("apiKey"));
      if (apiKey == null || apiKey.isBlank()) {
        return new PluginResult(false, "Qualifire API key is required", Map.of(), false, null);
      }
      Map<String, Object> response = postJson(
          baseUrl(parameters, QUALIFIRE_BASE_URL),
          body,
          Map.of("X-Qualifire-API-Key", apiKey));
      return PluginResult.withData(!"failed".equals(response.get("status")), objectMap(response.get("evaluationResults")));
    } catch (RuntimeException exception) {
      return new PluginResult(false, exception.getMessage(), Map.of(), false, null);
    }
  }

  private static List<Map<String, Object>> qualifireMessages(HookContext context) {
    List<Map<String, Object>> messages = new ArrayList<>();
    Object requestMessages = context.requestJson().get("messages");
    if (requestMessages instanceof List<?> list && !list.isEmpty()) {
      messages.add(qualifireMessage(objectMap(list.getLast())));
    }
    Object choices = context.responseJson().get("choices");
    if (choices instanceof List<?> list && !list.isEmpty()) {
      Map<String, Object> choice = objectMap(list.getFirst());
      Map<String, Object> message = objectMap(choice.get("message"));
      if (!message.isEmpty()) {
        messages.add(qualifireMessage(message));
      }
    } else if (!context.responseText().isBlank()) {
      messages.add(Map.of("role", "assistant", "content", context.responseText()));
    }
    return messages;
  }

  private static Map<String, Object> qualifireMessage(Map<String, Object> message) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("role", message.get("role"));
    result.put("content", qualifireContent(message.get("content")));
    Object toolCalls = qualifireToolCalls(message.get("tool_calls"));
    if (toolCalls != null) {
      result.put("tool_calls", toolCalls);
    }
    if (message.get("tool_call_id") != null) {
      result.put("tool_call_id", message.get("tool_call_id"));
    }
    return result;
  }

  private static String qualifireContent(Object content) {
    if (content == null) {
      return "";
    }
    if (content instanceof String text) {
      return text;
    }
    if (content instanceof List<?> parts) {
      return parts.stream()
          .map(part -> {
            Map<String, Object> partMap = objectMap(part);
            if ("text".equals(partMap.get("type"))) {
              return stringParameter(partMap.get("text"));
            }
            return "\n" + partMap + "\n";
          })
          .reduce("", String::concat);
    }
    return String.valueOf(content);
  }

  private static Object qualifireAvailableTools(Map<String, Object> requestJson) {
    Object toolsValue = requestJson.get("tools");
    if (!(toolsValue instanceof List<?> tools)) {
      return null;
    }
    List<Map<String, Object>> available = new ArrayList<>();
    for (Object toolValue : tools) {
      Map<String, Object> tool = objectMap(toolValue);
      if (!"function".equals(tool.get("type"))) {
        continue;
      }
      Map<String, Object> function = objectMap(tool.get("function"));
      available.add(Map.of(
          "name", function.get("name"),
          "description", function.get("description"),
          "parameters", JsonCopies.mutableDeepCopyValue(function.get("parameters"))));
    }
    return available.isEmpty() ? null : available;
  }

  private static Object qualifireToolCalls(Object toolCallsValue) {
    if (!(toolCallsValue instanceof List<?> toolCalls)) {
      return null;
    }
    List<Map<String, Object>> converted = new ArrayList<>();
    for (Object toolCallValue : toolCalls) {
      Map<String, Object> toolCall = objectMap(toolCallValue);
      if (!"function".equals(toolCall.get("type"))) {
        continue;
      }
      Map<String, Object> function = objectMap(toolCall.get("function"));
      converted.add(Map.of(
          "id", toolCall.get("id"),
          "name", function.get("name"),
          "arguments", function.get("arguments") == null ? Map.of() : function.get("arguments")));
    }
    return converted.isEmpty() ? null : converted;
  }

  private static PluginResult portkeyModerateContent(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "moderations", Map.of("input", currentText(context)), Map.of());
    Map<String, Object> result = objectMap(response.getOrDefault("response", response));
    List<String> restricted = stringListFlexible(parameters.get("categories"));
    Map<String, Object> first = firstResult(result);
    Map<String, Object> categories = objectMap(first.get("categories"));
    List<String> flagged = categories.entrySet().stream()
        .filter(entry -> booleanValue(entry.getValue()))
        .map(Map.Entry::getKey)
        .filter(category -> restricted.isEmpty() || restricted.contains(category))
        .toList();
    boolean not = booleanParameter(parameters, "not");
    boolean hasRestricted = !flagged.isEmpty();
    return PluginResult.withData(not ? hasRestricted : !hasRestricted, Map.of("flaggedCategories", flagged, "moderationResults", first));
  }

  private static PluginResult portkeyLanguage(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "language", Map.of("input", currentText(context)), Map.of());
    Object actual = response.getOrDefault("response", response.get("result"));
    String detected = nestedLabel(actual);
    List<String> allowed = stringListFlexible(parameters.get("language"));
    boolean not = booleanParameter(parameters, "not");
    boolean listed = allowed.contains(detected);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("detectedLanguage", detected);
    data.put("allowedLanguages", allowed);
    return PluginResult.withData(not ? !listed : listed, data);
  }

  private static PluginResult portkeyGibberish(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "gibberish", Map.of("input", currentText(context)), Map.of());
    String label = nestedLabel(response.getOrDefault("response", response.get("result")));
    boolean clean = "clean".equals(label);
    boolean not = booleanParameter(parameters, "not");
    return PluginResult.withData(not ? !clean : clean, Map.of("analysis", response));
  }

  private static PluginResult portkeyPii(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "pii", Map.of("input", textArray(context), "categories", stringListFlexible(parameters.get("categories"))), Map.of());
    Object raw = response.getOrDefault("response", response.get("results"));
    List<Map<String, Object>> results = raw instanceof List<?> list
        ? list.stream().filter(Map.class::isInstance).map(item -> stringObjectMap((Map<?, ?>) item)).toList()
        : List.of();
    List<String> detected = new ArrayList<>();
    List<String> redacted = new ArrayList<>();
    for (Map<String, Object> item : results) {
      Object entities = item.get("entities");
      if (entities instanceof List<?> entityList) {
        for (Object entity : entityList) {
          if (entity instanceof Map<?, ?> entityMap) {
            detected.addAll(objectMap(entityMap.get("labels")).keySet());
          }
        }
      }
      Object processed = item.get("processed_text");
      redacted.add(processed == null ? null : String.valueOf(processed));
    }
    List<String> restricted = stringListFlexible(parameters.get("categories"));
    boolean hasPii = detected.stream().anyMatch(category -> restricted.isEmpty() || restricted.contains(category));
    if (hasPii && booleanParameter(parameters, "redact")) {
      return new PluginResult(true, null, Map.of("detectedCategories", detected), true, transformedCurrentText(context, firstNonNull(redacted, currentText(context))));
    }
    boolean not = booleanParameter(parameters, "not");
    return PluginResult.withData(not ? hasPii : !hasPii, Map.of("detectedCategories", detected, "restrictedCategories", restricted));
  }

  private static PluginResult aporiaValidateProject(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messages", JsonCopies.mutableDeepCopyValue(context.requestJson().get("messages")));
    body.put("explain", true);
    body.put("validation_target", context.responseText().isBlank() ? "prompt" : "response");
    if (!context.responseText().isBlank()) {
      body.put("response", context.responseText());
    }
    String projectId = stringParameter(parameters.get("projectID"));
    String apiKey = stringParameter(credentials(parameters).get("apiKey"));
    String base = stringParameter(credentials(parameters).get("apiEndpoint"));
    Map<String, Object> response = postJson((base == null ? baseUrl(parameters, APORIA_BASE_URL) : base).replaceAll("/+$", "") + "/" + projectId + "/validate", body, Map.of("X-APORIA-API-KEY", apiKey == null ? "" : apiKey));
    return PluginResult.withData("passthrough".equals(response.get("action")), response);
  }

  private static PluginResult sydeguard(HookContext context, Map<String, ?> parameters) {
    String apiKey = stringParameter(credentials(parameters).get("apiKey"));
    Map<String, Object> response = postJson(baseUrl(parameters, SYDEGUARD_URL), Map.of("prompt", currentText(context)), Map.of("x-api-key", apiKey == null ? "" : apiKey));
    Map<String, Double> scores = new LinkedHashMap<>();
    Object categoryScores = response.get("category_scores");
    if (categoryScores instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> score = objectMap(item);
        Number value = score.get("score") instanceof Number number ? number : 0;
        scores.put(stringParameter(score.get("category")), value.doubleValue());
      }
    }
    boolean blocked = scores.getOrDefault("PROMPT_INJECT", 0.0) > doubleParameter(parameters.get("prompt_injection_threshold"), 1.0)
        || scores.getOrDefault("TOXIC", 0.0) > doubleParameter(parameters.get("toxicity_threshold"), 1.0)
        || scores.getOrDefault("EVASION", 0.0) > doubleParameter(parameters.get("evasion_threshold"), 1.0);
    return PluginResult.withData(!blocked, response);
  }

  private static PluginResult pillar(HookContext context, Map<String, ?> parameters, String path) {
    List<String> scanners = stringListFlexible(parameters.get("scanners"));
    if (scanners.isEmpty()) {
      return new PluginResult(true, "No scanners specified", Map.of(), false, null);
    }
    Map<String, Object> scannerObject = new LinkedHashMap<>();
    scanners.forEach(scanner -> scannerObject.put(scanner, true));
    String apiKey = stringParameter(credentials(parameters).get("apiKey"));
    Map<String, Object> response = postJson(baseUrl(parameters, PILLAR_BASE_URL).replaceAll("/+$", "") + "/" + path, Map.of("message", currentText(context), "scanners", scannerObject), bearer(apiKey));
    boolean blocked = response.entrySet().stream()
        .filter(entry -> Set.of("pii", "prompt_injection", "secrets", "toxic_language", "invisible_characters").contains(entry.getKey()))
        .anyMatch(entry -> entry.getValue() != null && !Boolean.FALSE.equals(entry.getValue()));
    return PluginResult.withData(!blocked, response);
  }

  private static PluginResult patronusJudge(HookContext context, Map<String, ?> parameters, String evaluator, String criteria) {
    Map<String, Object> response = postPatronus(parameters, evaluator, criteria, Map.of("input", extractRequestText(context.requestJson()), "output", context.responseText()));
    Map<String, Object> evalResult = firstResult(response);
    Map<String, Object> evaluation = objectMap(evalResult.get("evaluation_result"));
    return new PluginResult(booleanValue(evaluation.get("pass")), stringParameter(evalResult.get("error_message")), objectMap(evaluation.get("additional_info")), false, null);
  }

  private static PluginResult patronusCustom(HookContext context, Map<String, ?> parameters) {
    String profile = stringParameter(parameters.containsKey("profile") ? parameters.get("profile") : parameters.get("criteria"));
    if (profile == null || profile.isBlank()) {
      return new PluginResult(true, "Profile parameter is required.", Map.of(), false, null);
    }
    String evaluator = "judge";
    String criteria = profile;
    int separator = profile.indexOf(':');
    if (separator > 0) {
      evaluator = profile.substring(0, separator);
      criteria = profile.substring(separator + 1);
    }
    return patronusJudge(context, parameters, evaluator, criteria);
  }

  private static PluginResult patronusPiiLike(HookContext context, Map<String, ?> parameters, String evaluator) {
    Map<String, Object> response = postPatronus(parameters, evaluator, null, Map.of("output", currentText(context)));
    Map<String, Object> evalResult = firstResult(response);
    Map<String, Object> evaluation = objectMap(evalResult.get("evaluation_result"));
    boolean passed = booleanValue(evaluation.get("pass"));
    Map<String, Object> info = objectMap(evaluation.get("additional_info"));
    if (!passed && booleanParameter(parameters, "redact")) {
      String masked = maskPositions(currentText(context), info.get("positions"));
      return new PluginResult(true, stringParameter(evalResult.get("error_message")), info, true, transformedCurrentText(context, masked));
    }
    return new PluginResult(passed, stringParameter(evalResult.get("error_message")), info, false, null);
  }

  private static Map<String, Object> postPatronus(Map<String, ?> parameters, String evaluator, String criteria, Map<String, ?> data) {
    Map<String, Object> evaluatorConfig = new LinkedHashMap<>();
    evaluatorConfig.put("evaluator", evaluator);
    evaluatorConfig.put("explain_strategy", "always");
    if (criteria != null && !criteria.isBlank()) {
      evaluatorConfig.put("criteria", criteria);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("evaluators", List.of(evaluatorConfig));
    body.put("evaluated_model_input", data.get("input"));
    body.put("evaluated_model_output", data.get("output"));
    return postJson(baseUrl(parameters, PATRONUS_BASE_URL), body, Map.of("x-api-key", stringParameter(credentials(parameters).get("apiKey"))));
  }

  private static PluginResult mistralModerateContent(HookContext context, Map<String, ?> parameters) {
    String apiKey = stringParameter(credentials(parameters).get("apiKey"));
    Map<String, Object> response = postJson(baseUrl(parameters, MISTRAL_BASE_URL).replaceAll("/+$", "") + "/v1/moderations", Map.of("model", parameterOrDefault(parameters, "model", "mistral-moderation-latest"), "input", List.of(currentText(context))), bearer(apiKey));
    Map<String, Object> categories = objectMap(firstResult(response).get("categories"));
    List<String> checks = stringListFlexible(parameters.get("categories"));
    boolean blocked = categories.entrySet().stream().anyMatch(entry -> (checks.isEmpty() || checks.contains(entry.getKey())) && booleanValue(entry.getValue()));
    return PluginResult.withData(!blocked, blocked ? Map.of("flagged_categories", categories.keySet()) : Map.of());
  }

  private static PluginResult pangeaTextGuard(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> creds = credentials(parameters);
    String url = baseUrl(parameters, "https://ai-guard." + stringParameter(creds.get("domain")) + "/v1/text/guard");
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("text", currentText(context));
    request.put("recipe", parameters.get("recipe"));
    request.put("debug", parameters.get("debug"));
    request.put("overrides", parameters.get("overrides"));
    Map<String, Object> response = postJson(url, request, bearer(stringParameter(creds.get("apiKey"))));
    Map<String, Object> result = objectMap(response.get("result"));
    Map<String, Object> detectors = objectMap(result.get("detectors"));
    boolean blocked = detectors.values().stream().map(DefaultPluginRegistry::objectMap).anyMatch(detector -> booleanValue(detector.get("detected")));
    return PluginResult.withData(!blocked, result);
  }

  private static PluginResult pangeaPii(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> creds = credentials(parameters);
    String url = baseUrl(parameters, "https://redact." + stringParameter(creds.get("domain")) + "/v1/redact_structured");
    Map<String, Object> response = postJson(url, Map.of("data", textArray(context)), bearer(stringParameter(creds.get("apiKey"))));
    Map<String, Object> result = objectMap(response.get("result"));
    boolean detected = longParameter(result.get("count"), 0) > 0 && result.get("redacted_data") instanceof List<?>;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", response.get("summary"));
    if (detected && booleanParameter(parameters, "redact")) {
      List<String> redacted = stringValues(result.get("redacted_data"));
      return new PluginResult(true, null, data, true, transformedCurrentText(context, firstNonNull(redacted, currentText(context))));
    }
    return PluginResult.withData(!detected, data);
  }

  private static PluginResult bedrockGuard(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "bedrock/guard", Map.of("content", List.of(Map.of("text", Map.of("text", currentText(context)))), "source", context.responseText().isBlank() ? "INPUT" : "OUTPUT"), Map.of());
    boolean intervened = "GUARDRAIL_INTERVENED".equals(response.get("action"));
    if (intervened && booleanParameter(parameters, "redact")) {
      return new PluginResult(true, null, response, true, transformedCurrentText(context, currentText(context)));
    }
    return PluginResult.withData(!intervened, response);
  }

  private static PluginResult acuvityScan(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "_acuvity/scan", Map.of("anonymization", "FixedSize", "messages", textArray(context), "redactions", stringListFlexible(parameters.get("redactions")), "type", context.responseText().isBlank() ? "Input" : "Output"), bearer(stringParameter(credentials(parameters).get("apiKey"))));
    Object extractionsValue = response.get("extractions");
    List<Map<String, Object>> extractions = extractionsValue instanceof List<?> list
        ? list.stream().filter(Map.class::isInstance).map(item -> stringObjectMap((Map<?, ?>) item)).toList()
        : List.of();
    boolean blocked = extractions.stream().anyMatch(extraction -> booleanValue(extraction.get("matched")) || !objectMap(extraction.get("guards")).isEmpty());
    if (booleanParameter(parameters, "redact") && !extractions.isEmpty()) {
      return new PluginResult(!blocked, null, Map.of("guards", extractions.toString()), true, transformedCurrentText(context, stringParameter(extractions.getFirst().getOrDefault("data", currentText(context)))));
    }
    return PluginResult.withData(!blocked, Map.of("guards", extractions.toString()));
  }

  private static PluginResult lassoClassify(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> body = Map.of("messages", JsonCopies.mutableDeepCopyValue(context.requestJson().getOrDefault("messages", List.of())));
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("lasso-api-key", stringParameter(credentials(parameters).get("apiKey")));
    maybeHeader(headers, "lasso-conversation-id", parameters.get("conversationId"));
    maybeHeader(headers, "lasso-user-id", parameters.get("userId"));
    Map<String, Object> response = postJson(baseUrl(parameters, LASSO_BASE_URL).replaceAll("/+$", "") + "/gateway/v2/classify", body, headers);
    return PluginResult.withData(!booleanValue(response.get("violations_detected")), response);
  }

  private static PluginResult exaOnline(HookContext context, Map<String, ?> parameters) {
    if (!context.responseText().isBlank() || (!"chatComplete".equals(context.requestType()) && !"complete".equals(context.requestType()))) {
      return PluginResult.pass();
    }
    String apiKey = stringParameter(credentials(parameters).get("apiKey"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", currentText(context));
    body.put("numResults", parameterOrDefault(parameters, "numResults", 10));
    body.put("useAutoprompt", true);
    body.put("contents", Map.of("text", true));
    Map<String, Object> response = postJson(baseUrl(parameters, EXA_SEARCH_URL), body, Map.of("x-api-key", apiKey == null ? "" : apiKey, "Content-Type", "application/json"));
    Object resultsValue = response.get("results");
    if (!(resultsValue instanceof List<?> results) || results.isEmpty()) {
      return PluginResult.withData(true, response);
    }
    StringBuilder contextText = new StringBuilder("\n<web_search_context>\n");
    for (int index = 0; index < results.size(); index++) {
      Map<String, Object> result = objectMap(results.get(index));
      contextText.append("[").append(index + 1).append("] \"").append(result.get("title")).append("\"\n");
      contextText.append("URL: ").append(result.get("url")).append("\n");
      contextText.append(result.get("text")).append("\n");
    }
    contextText.append("</web_search_context>");
    Map<String, Object> transformed = JsonCopies.mutableDeepCopyMap(context.requestJson());
    if (transformed.get("messages") instanceof List<?> messages) {
      List<Object> next = new ArrayList<>(messages);
      int systemIndex = -1;
      for (int index = 0; index < next.size(); index++) {
        if (next.get(index) instanceof Map<?, ?> message && "system".equals(message.get("role"))) {
          systemIndex = index;
          break;
        }
      }
      if (systemIndex >= 0 && next.get(systemIndex) instanceof Map<?, ?> messageMap) {
        Map<String, Object> message = stringObjectMap(messageMap);
        message.put("content", String.valueOf(message.getOrDefault("content", "")) + contextText);
        next.set(systemIndex, message);
      } else {
        next.addFirst(Map.of("role", "system", "content", contextText.toString()));
      }
      transformed.put("messages", next);
    } else if (transformed.get("prompt") instanceof String prompt) {
      transformed.put("prompt", contextText + prompt);
    }
    return new PluginResult(true, null, response, true, new TransformedData(transformed));
  }

  private static PluginResult azureContentSafety(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postAzure(parameters, "contentsafety/text:analyze?api-version=" + parameterOrDefault(parameters, "apiVersion", "2024-11-01"), Map.of("text", currentText(context), "categories", parameterOrDefault(parameters, "categories", List.of("Hate", "SelfHarm", "Sexual", "Violence")), "blocklistNames", parameterOrDefault(parameters, "blocklistNames", List.of())));
    Object categories = response.get("categoriesAnalysis");
    boolean harmful = categories instanceof List<?> list && list.stream().map(DefaultPluginRegistry::objectMap).anyMatch(category -> longParameter(category.get("severity"), 0) >= longParameter(parameters.get("severity"), 2));
    boolean blocklisted = response.get("blocklistsMatch") instanceof List<?> list && !list.isEmpty();
    return PluginResult.withData(!(harmful || blocklisted), response);
  }

  private static PluginResult azureProtectedMaterial(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postAzure(parameters, "contentsafety/text:detectProtectedMaterial?api-version=" + parameterOrDefault(parameters, "apiVersion", "2024-09-01"), Map.of("text", currentText(context)));
    boolean detected = booleanValue(objectMap(response.get("protectedMaterialAnalysis")).get("detected"));
    return PluginResult.withData(!detected, response);
  }

  private static PluginResult azureShieldPrompt(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postAzure(parameters, "contentsafety/text:shieldPrompt?api-version=" + parameterOrDefault(parameters, "apiVersion", "2024-09-01"), Map.of("userPrompt", currentText(context)));
    boolean userAttack = booleanValue(objectMap(response.get("userPromptAnalysis")).get("attackDetected"));
    boolean docAttack = response.get("documentsAnalysis") instanceof List<?> list && list.stream().map(DefaultPluginRegistry::objectMap).anyMatch(doc -> booleanValue(doc.get("attackDetected")));
    return PluginResult.withData(!(userAttack || docAttack), response);
  }

  private static PluginResult azurePii(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postAzure(parameters, "language/:analyze-text?api-version=" + parameterOrDefault(parameters, "apiVersion", "2024-11-01"), Map.of("kind", "PiiEntityRecognition", "analysisInput", Map.of("documents", List.of(Map.of("id", "0", "text", currentText(context), "language", parameterOrDefault(parameters, "language", "en"))))));
    Object docs = objectMap(response.get("results")).get("documents");
    List<Map<String, Object>> documents = docs instanceof List<?> list
        ? list.stream().filter(Map.class::isInstance).map(item -> stringObjectMap((Map<?, ?>) item)).toList()
        : List.of();
    boolean containsPii = documents.stream().anyMatch(doc -> doc.get("entities") instanceof List<?> entities && !entities.isEmpty());
    if (containsPii && booleanParameter(parameters, "redact")) {
      String redacted = documents.isEmpty() ? currentText(context) : stringParameter(documents.getFirst().getOrDefault("redactedText", currentText(context)));
      return new PluginResult(true, null, Map.of("documents", documents), true, transformedCurrentText(context, redacted));
    }
    return PluginResult.withData(!containsPii, Map.of("documents", documents));
  }

  private static Map<String, Object> postAzure(Map<String, ?> parameters, String path, Map<String, ?> body) {
    Map<String, Object> creds = objectMap(credentials(parameters).get("contentSafety"));
    if (creds.isEmpty()) {
      creds = objectMap(credentials(parameters).get("pii"));
    }
    String customHost = stringParameter(creds.get("customHost"));
    String resourceName = stringParameter(creds.get("resourceName"));
    String base = customHost == null ? "https://" + resourceName + ".cognitiveservices.azure.com" : customHost;
    String apiKey = stringParameter(creds.get("apiKey"));
    return postJson(baseUrl(parameters, base).replaceAll("/+$", "") + "/" + path, JsonCopies.deepCopyMap(body), Map.of("Ocp-Apim-Subscription-Key", apiKey == null ? "" : apiKey, "Content-Type", "application/json"));
  }

  private static PluginResult promptSecurity(HookContext context, Map<String, ?> parameters, String target) {
    Map<String, Object> body = Map.of(target, currentText(context));
    Map<String, Object> response = postPartnerJson(parameters, "api/protect", body, Map.of("APP-ID", stringParameter(credentials(parameters).get("apiKey"))));
    Map<String, Object> data = objectMap(objectMap(response.get("result")).get(target));
    return PluginResult.withData(booleanValue(data.get("passed")), data);
  }

  private static PluginResult panwPrismaAirs(HookContext context, Map<String, ?> parameters) {
    String apiKey = stringParameter(credentials(parameters).get("AIRS_API_KEY"));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tr_id", headerValue(context.headers(), "x-modelgate-trace-id") == null ? "trace" : headerValue(context.headers(), "x-modelgate-trace-id"));
    payload.put("metadata", Map.of("ai_model", parameterOrDefault(parameters, "ai_model", "unknown-model"), "app_user", parameterOrDefault(parameters, "app_user", "portkey-gateway"), "app_name", parameterOrDefault(parameters, "app_name", "Portkey")));
    payload.put("contents", List.of(context.responseText().isBlank() ? Map.of("prompt", currentText(context)) : Map.of("response", currentText(context))));
    Map<String, Object> response = postJson(baseUrl(parameters, PANW_AIRS_URL), payload, Map.of("x-pan-token", apiKey == null ? "" : apiKey));
    return PluginResult.withData(!"block".equals(response.get("action")), response);
  }

  private static PluginResult crowdstrikeAidrGuardChatCompletions(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> creds = credentials(parameters);
    String baseUrl = stringParameter(creds.get("baseUrl"));
    if (baseUrl == null || baseUrl.isBlank()) {
      return new PluginResult(true, "'parameters.credentials.baseUrl' must be set", Map.of(), false, null);
    }
    String apiKey = stringParameter(creds.get("apiKey"));
    if (apiKey == null || apiKey.isBlank()) {
      return new PluginResult(true, "'parameters.credentials.apiKey' must be set", Map.of(), false, null);
    }
    boolean outputHook = !context.responseText().isBlank() || !context.responseJson().isEmpty();
    Map<String, Object> guardInput = outputHook ? context.responseJson() : context.requestJson();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("guard_input", guardInput);
    body.put("event_type", outputHook ? "output" : "input");
    body.put("app_id", "Portkey AI Gateway");
    try {
      Map<String, Object> response = postJson(
          baseUrl.replaceAll("/+$", "") + "/v1/guard_chat_completions",
          body,
          Map.of(
              "Content-Type", "application/json",
              "User-Agent", "portkey-ai-plugin/v1.0.0-beta",
              "Authorization", "Bearer " + apiKey));
      if (!"Success".equals(response.get("status"))) {
        return new PluginResult(true, crowdstrikeAidrError(response), Map.of(), false, null);
      }
      Map<String, Object> result = objectMap(response.get("result"));
      if (result.isEmpty()) {
        return new PluginResult(true, "Missing result from response body: " + response, Map.of(), false, null);
      }
      String policy = stringParameter(result.get("policy"));
      if (booleanValue(result.get("blocked"))) {
        return PluginResult.withData(false, Map.of("explanation", "Blocked by AIDR Policy '" + policy + "'"));
      }
      Map<String, Object> data = Map.of("explanation", "Allowed by AIDR policy '" + policy + "', but requires transformations");
      if (!booleanValue(result.get("transformed"))) {
        return PluginResult.withData(true, Map.of("explanation", "Allowed by AIDR Policy '" + policy + "'"));
      }
      Map<String, Object> guardOutput = objectMap(result.get("guard_output"));
      TransformedData transformedData = outputHook
          ? TransformedData.forResponse(guardOutput)
          : new TransformedData(guardOutput);
      return new PluginResult(true, null, data, true, transformedData);
    } catch (RuntimeException exception) {
      return new PluginResult(true, exception.getMessage(), Map.of(), false, null);
    }
  }

  private static String crowdstrikeAidrError(Map<String, Object> response) {
    StringBuilder builder = new StringBuilder();
    builder.append("Summary: ").append(response.get("summary")).append('\n');
    builder.append("status: ").append(response.get("status")).append('\n');
    builder.append("request_id: ").append(response.get("request_id")).append('\n');
    builder.append("request_time: ").append(response.get("request_time")).append('\n');
    builder.append("response_time: ").append(response.get("response_time")).append('\n');
    Object errors = objectMap(response.get("result")).get("errors");
    if (errors instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> error = objectMap(item);
        builder.append('\t')
            .append(error.get("source"))
            .append(' ')
            .append(error.get("code"))
            .append(": ")
            .append(error.get("detail"))
            .append('\n');
      }
    }
    return builder.toString();
  }

  private static PluginResult walledAi(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postJson(baseUrl(parameters, WALLED_AI_URL), Map.of("text", currentText(context), "generic_safety_check", parameterOrDefault(parameters, "generic_safety_check", true)), Map.of("x-api-key", stringParameter(credentials(parameters).get("apiKey"))));
    Map<String, Object> data = objectMap(response.get("data"));
    Object safety = data.get("safety");
    boolean unsafe = safety instanceof List<?> list && !list.isEmpty() && Boolean.FALSE.equals(objectMap(list.getFirst()).get("isSafe"));
    return PluginResult.withData(!unsafe, data);
  }

  private static PluginResult javelinGuardrails(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "v1/guardrails/apply", Map.of("input", Map.of("text", currentText(context)), "config", Map.of(), "metadata", Map.of()), Map.of("x-javelin-apikey", stringParameter(credentials(parameters).get("apiKey")), "x-javelin-application", stringParameter(credentials(parameters).get("application"))));
    Object assessmentsValue = response.get("assessments");
    List<Map<String, Object>> flagged = new ArrayList<>();
    if (assessmentsValue instanceof List<?> assessments) {
      for (Object assessment : assessments) {
        Map<String, Object> assessmentMap = objectMap(assessment);
        for (Map.Entry<String, Object> entry : assessmentMap.entrySet()) {
          Map<String, Object> data = objectMap(entry.getValue());
          if (booleanValue(data.get("request_reject"))) {
            flagged.add(Map.of("type", entry.getKey(), "request_reject", true));
          }
        }
      }
    }
    return PluginResult.withData(flagged.isEmpty(), flagged.isEmpty() ? Map.of("all_passed", true) : Map.of("flagged_assessments", flagged, "javelin_response", response));
  }

  private static PluginResult f5Guardrails(HookContext context, Map<String, ?> parameters) {
    Map<String, Object> response = postPartnerJson(parameters, "backend/v1/scans", Map.of("input", currentText(context), "project", parameters.get("projectId")), bearer(stringParameter(credentials(parameters).get("apiKey"))));
    Map<String, Object> result = objectMap(response.get("result"));
    String outcome = stringParameter(result.get("outcome"));
    boolean flagged = outcome != null && !"cleared".equals(outcome);
    if (booleanParameter(parameters, "redact") && response.get("redactedInput") != null) {
      return new PluginResult(true, null, result, true, transformedCurrentText(context, String.valueOf(response.get("redactedInput"))));
    }
    return PluginResult.withData(!flagged, response);
  }

  private static Map<String, Object> postPartnerJson(Map<String, ?> parameters, String path, Map<String, ?> body, Map<String, String> headers) {
    return postJson(baseUrl(parameters, "").replaceAll("/+$", "") + "/" + path.replaceAll("^/+", ""), JsonCopies.deepCopyMap(body), headers);
  }

  private static Object parameterOrDefault(Map<String, ?> parameters, String key, Object defaultValue) {
    Object value = parameters.get(key);
    return value == null ? defaultValue : value;
  }

  private static String baseUrl(Map<String, ?> parameters, String defaultValue) {
    String value = stringParameter(parameters.get("baseUrl"));
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static Map<String, Object> credentials(Map<String, ?> parameters) {
    return objectMap(parameters.get("credentials"));
  }

  private static Map<String, String> bearer(String apiKey) {
    return apiKey == null ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
  }

  private static void maybeHeader(Map<String, String> headers, String name, Object value) {
    if (value != null) {
      headers.put(name, String.valueOf(value));
    }
  }

  private static List<String> textArray(HookContext context) {
    String text = currentText(context);
    return text.isBlank() ? List.of() : List.of(text);
  }

  private static Map<String, Object> firstResult(Map<String, Object> response) {
    Object resultsValue = response.get("results");
    if (resultsValue instanceof List<?> results && !results.isEmpty()) {
      return objectMap(results.getFirst());
    }
    return Map.of();
  }

  private static String nestedLabel(Object value) {
    if (value instanceof List<?> outer && !outer.isEmpty()) {
      Object first = outer.getFirst();
      if (first instanceof List<?> inner && !inner.isEmpty()) {
        return stringParameter(objectMap(inner.getFirst()).get("label"));
      }
      return stringParameter(objectMap(first).get("label"));
    }
    return stringParameter(objectMap(value).get("label"));
  }

  private static String firstNonNull(List<String> values, String fallback) {
    for (String value : values) {
      if (value != null) {
        return value;
      }
    }
    return fallback;
  }

  private static String maskPositions(String text, Object positionsValue) {
    if (!(positionsValue instanceof List<?> positions)) {
      return text;
    }
    String masked = text;
    List<List<Integer>> ranges = new ArrayList<>();
    for (Object position : positions) {
      if (position instanceof List<?> range && range.size() >= 2) {
        ranges.add(List.of(((Number) range.get(0)).intValue(), ((Number) range.get(1)).intValue()));
      }
    }
    ranges.sort((left, right) -> Integer.compare(right.get(0), left.get(0)));
    for (List<Integer> range : ranges) {
      int start = Math.max(0, Math.min(masked.length(), range.get(0)));
      int end = Math.max(start, Math.min(masked.length(), range.get(1)));
      masked = masked.substring(0, start) + "*".repeat(Math.max(1, end - start)) + masked.substring(end);
    }
    return masked;
  }

  private static double doubleParameter(Object value, double defaultValue) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value == null) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static Map<String, Object> hookPayload(HookContext context) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("request", Map.of("json", context.requestJson(), "headers", context.headers()));
    payload.put("response", Map.of("text", context.responseText()));
    payload.put("metadata", context.metadata());
    payload.put("requestType", context.requestType());
    return payload;
  }

  private static Map<String, Object> postJson(String url, Map<String, Object> payload, Map<String, String> headers) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(5))
          .header("content-type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload)));
      headers.forEach(builder::header);
      HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalArgumentException("HTTP error! status: " + response.statusCode());
      }
      if (response.body() == null || response.body().isBlank()) {
        return Map.of();
      }
      return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid JSON response", exception);
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException(exception.getMessage(), exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Interrupted while calling webhook", exception);
    }
  }

  private static Map<String, String> headerParameters(Map<String, ?> parameters) {
    Map<String, Object> headers = objectMap(parameters.get("headers"));
    Map<String, String> result = new LinkedHashMap<>();
    headers.forEach((key, value) -> result.put(key, String.valueOf(value)));
    return result;
  }

  private static TransformedData transformedData(Object value) {
    if (!(value instanceof Map<?, ?> transformedData)) {
      return null;
    }
    Map<String, Object> transformedRequestJson = Map.of();
    Object requestValue = transformedData.get("request");
    if (requestValue instanceof Map<?, ?> request && request.get("json") instanceof Map<?, ?> requestJson) {
      transformedRequestJson = stringObjectMap(requestJson);
    }
    Map<String, Object> transformedResponseJson = Map.of();
    Object responseValue = transformedData.get("response");
    if (responseValue instanceof Map<?, ?> response && response.get("json") instanceof Map<?, ?> responseJson) {
      transformedResponseJson = stringObjectMap(responseJson);
    }
    return transformedRequestJson.isEmpty() && transformedResponseJson.isEmpty()
        ? null
        : new TransformedData(transformedRequestJson, transformedResponseJson);
  }

  private static String headerValue(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> header : headers.entrySet()) {
      if (header.getKey().equalsIgnoreCase(name)) {
        return header.getValue();
      }
    }
    return null;
  }

  private static PluginResult regexMatch(HookContext context, Map<String, ?> parameters) {
    String regexPattern = stringParameter(parameters.get("rule"));
    boolean not = booleanParameter(parameters, "not");
    String textToMatch = context.responseText();
    try {
      if (regexPattern == null || regexPattern.isEmpty()) {
        throw new IllegalArgumentException("Missing regex pattern");
      }
      if (textToMatch == null || textToMatch.isEmpty()) {
        throw new IllegalArgumentException("Missing text to match");
      }

      Matcher matcher = Pattern.compile(regexPattern).matcher(textToMatch);
      boolean matches = matcher.find();
      boolean verdict = not ? !matches : matches;
      Map<String, Object> data = regexMatchData(regexPattern, not, verdict, textToMatch, matches ? matcher : null);
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      return new PluginResult(false, exception.getMessage(), regexErrorData(regexPattern, not, textToMatch, exception), false, null);
    }
  }

  private static Map<String, Object> regexMatchData(
      String regexPattern,
      boolean not,
      boolean verdict,
      String textToMatch,
      Matcher matcher) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("regexPattern", regexPattern);
    data.put("not", not);
    data.put("verdict", verdict);
    data.put("explanation", regexExplanation(regexPattern, not, verdict));
    data.put("matchDetails", matcher == null ? null : matchDetails(matcher));
    data.put("textExcerpt", textExcerpt(textToMatch));
    return data;
  }

  private static String regexExplanation(String regexPattern, boolean not, boolean verdict) {
    if (verdict && not) {
      return "The regex pattern '%s' did not match the text as expected.".formatted(regexPattern);
    }
    if (verdict) {
      return "The regex pattern '%s' successfully matched the text.".formatted(regexPattern);
    }
    if (not) {
      return "The regex pattern '%s' matched the text when it should not have.".formatted(regexPattern);
    }
    return "The regex pattern '%s' did not match the text.".formatted(regexPattern);
  }

  private static Map<String, Object> matchDetails(Matcher matcher) {
    List<String> captures = new ArrayList<>();
    for (int groupIndex = 1; groupIndex <= matcher.groupCount(); groupIndex++) {
      captures.add(matcher.group(groupIndex));
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("matchedText", matcher.group());
    details.put("index", matcher.start());
    details.put("groups", namedGroups(matcher));
    details.put("captures", captures);
    return details;
  }

  private static Map<String, Object> namedGroups(Matcher matcher) {
    Map<String, Object> groups = new LinkedHashMap<>();
    Matcher groupNameMatcher = NAMED_GROUP_PATTERN.matcher(matcher.pattern().pattern());
    while (groupNameMatcher.find()) {
      String groupName = groupNameMatcher.group(1);
      try {
        groups.put(groupName, matcher.group(groupName));
      } catch (IllegalArgumentException ignored) {
        // Ignore names that appear in escaped text or unsupported constructs.
      }
    }
    return groups;
  }

  private static Map<String, Object> regexErrorData(
      String regexPattern,
      boolean not,
      String textToMatch,
      RuntimeException exception) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("explanation", "An error occurred while processing the regex: " + exception.getMessage());
    data.put("regexPattern", regexPattern);
    data.put("not", not);
    data.put("textExcerpt", textToMatch == null || textToMatch.isBlank() ? "No text available" : textExcerpt(textToMatch));
    return data;
  }

  private static String textExcerpt(String text) {
    if (text == null) {
      return "";
    }
    return text.length() > 100 ? text.substring(0, 100) + "..." : text;
  }

  private static PluginResult characterCount(HookContext context, Map<String, ?> parameters) {
    Integer minCharacters = integerParameterOrNull(parameters.get("minCharacters"));
    Integer maxCharacters = integerParameterOrNull(parameters.get("maxCharacters"));
    boolean not = booleanParameter(parameters, "not");
    String text = context.responseText();
    try {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("Missing text to analyze");
      }
      if (minCharacters == null || maxCharacters == null) {
        throw new IllegalArgumentException("Invalid or missing character count range");
      }
      int count = text.length();
      boolean inRange = count >= minCharacters && count <= maxCharacters;
      boolean verdict = not ? !inRange : inRange;

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("characterCount", count);
      data.put("minCharacters", minCharacters);
      data.put("maxCharacters", maxCharacters);
      data.put("not", not);
      data.put("verdict", verdict);
      data.put("explanation", countExplanation("characters", count, minCharacters, maxCharacters, not, verdict));
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while counting characters: " + exception.getMessage());
      data.put("minCharacters", minCharacters);
      data.put("maxCharacters", maxCharacters);
      data.put("not", not);
      data.put("textExcerpt", text == null || text.isEmpty() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static PluginResult sentenceCount(HookContext context, Map<String, ?> parameters) {
    Integer minCount = integerParameterOrNull(parameters.get("minSentences"));
    Integer maxCount = integerParameterOrNull(parameters.get("maxSentences"));
    boolean not = booleanParameter(parameters, "not");
    String text = context.responseText() == null ? "" : context.responseText();
    try {
      if (minCount == null || maxCount == null) {
        throw new IllegalArgumentException("Missing sentence count range");
      }
      int count = (int) text.chars().filter(character -> character == '.' || character == '!' || character == '?').count();
      boolean inRange = count >= minCount && count <= maxCount;
      boolean verdict = not ? !inRange : inRange;

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("sentenceCount", count);
      data.put("minCount", minCount);
      data.put("maxCount", maxCount);
      data.put("not", not);
      data.put("verdict", verdict);
      data.put("explanation", sentenceExplanation(count, minCount, maxCount, not, verdict));
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred: " + exception.getMessage());
      data.put("minCount", minCount);
      data.put("maxCount", maxCount);
      data.put("not", not);
      data.put("textExcerpt", text.isEmpty() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static PluginResult endsWith(HookContext context, Map<String, ?> parameters) {
    String suffix = stringParameter(parameters.get("suffix"));
    boolean not = booleanParameter(parameters, "not");
    String text = context.responseText();
    try {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("Missing text to analyze");
      }
      if (suffix == null || suffix.isEmpty()) {
        throw new IllegalArgumentException("Missing or empty suffix");
      }
      boolean exactSuffix = text.endsWith(suffix);
      boolean trailingPeriodSuffix = text.endsWith(suffix + ".");
      boolean matches = exactSuffix || trailingPeriodSuffix;
      boolean verdict = not ? !matches : matches;

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("suffix", suffix);
      data.put("not", not);
      data.put("verdict", verdict);
      data.put("explanation", endsWithExplanation(suffix, not, verdict, trailingPeriodSuffix));
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while checking suffix: " + exception.getMessage());
      data.put("suffix", suffix);
      data.put("not", not);
      data.put("textExcerpt", text == null || text.isEmpty() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static PluginResult allUpperCase(HookContext context, Map<String, ?> parameters) {
    return caseCheck(
        context,
        parameters,
        true,
        "All alphabetic characters in the text are uppercase.",
        "The text contains lowercase characters.",
        "The text contains lowercase characters as expected.",
        "All alphabetic characters in the text are uppercase when they should not be.");
  }

  private static PluginResult allLowerCase(HookContext context, Map<String, ?> parameters) {
    return caseCheck(
        context,
        parameters,
        false,
        "All alphabetic characters in the text are lowercase.",
        "The text contains uppercase characters.",
        "The text contains uppercase characters as expected.",
        "All alphabetic characters in the text are lowercase when they should not be.");
  }

  private static PluginResult caseCheck(
      HookContext context,
      Map<String, ?> parameters,
      boolean upperCase,
      String passExplanation,
      String failExplanation,
      String notPassExplanation,
      String notFailExplanation) {
    boolean not = booleanParameter(parameters, "not");
    String text = context.responseText();
    try {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("Missing text to analyze");
      }
      String letters = text.replaceAll("[^a-zA-Z]", "");
      boolean matches = upperCase
          ? letters.equals(letters.toUpperCase(Locale.ROOT))
          : letters.equals(letters.toLowerCase(Locale.ROOT));
      boolean verdict = not ? !matches : matches;

      Map<String, Object> data = new LinkedHashMap<>();
      data.put("verdict", verdict);
      data.put("not", not);
      data.put("explanation", verdict ? (not ? notPassExplanation : passExplanation) : (not ? notFailExplanation : failExplanation));
      data.put("textExcerpt", textExcerpt(text));
      return PluginResult.withData(verdict, data);
    } catch (IllegalArgumentException exception) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("explanation", "An error occurred while checking "
          + (upperCase ? "uppercase" : "lowercase")
          + ": "
          + exception.getMessage());
      data.put("not", not);
      data.put("textExcerpt", text == null || text.isEmpty() ? "No text available" : textExcerpt(text));
      return new PluginResult(false, exception.getMessage(), data, false, null);
    }
  }

  private static PluginResult notNull(HookContext context, Map<String, ?> parameters) {
    boolean not = booleanParameter(parameters, "not");
    CurrentContent content = currentResponseContent(context);
    boolean isNull = content.content() == null
        || (content.content() instanceof String text && text.trim().isEmpty())
        || (content.content() instanceof List<?> list && list.isEmpty())
        || content.textArray().stream().allMatch(text -> text == null || text.trim().isEmpty());
    boolean verdict = not ? isNull : !isNull;

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("isNull", isNull);
    data.put("contentType", contentType(content.content()));
    data.put("textArrayLength", content.textArray().size());
    data.put("explanation", isNull ? "Content is null, undefined, or empty." : "Content exists and is not null.");
    data.put("verdict", verdict ? "passed" : "failed");
    return PluginResult.withData(verdict, data);
  }

  private record CurrentContent(Object content, List<String> textArray) {}

  private static CurrentContent currentResponseContent(HookContext context) {
    if (!context.responseJson().isEmpty()) {
      return switch (context.requestType() == null ? "" : context.requestType()) {
        case "chatComplete" -> chatCompletionContent(context.responseJson());
        case "complete" -> completionContent(context.responseJson());
        case "messages" -> anthropicMessagesContent(context.responseJson());
        default -> new CurrentContent(context.responseJson(), List.of(context.responseJson().toString()));
      };
    }
    String text = context.responseText();
    return new CurrentContent(text, text == null || text.isEmpty() ? List.of() : List.of(text));
  }

  private static CurrentContent chatCompletionContent(Map<String, Object> responseJson) {
    Object choicesValue = responseJson.get("choices");
    if (!(choicesValue instanceof List<?> choices) || choices.isEmpty() || !(choices.getFirst() instanceof Map<?, ?> choice)) {
      return new CurrentContent(null, List.of());
    }
    Object messageValue = choice.get("message");
    if (!(messageValue instanceof Map<?, ?> message)) {
      return new CurrentContent(null, List.of());
    }
    Object content = message.get("content");
    return new CurrentContent(content, content == null ? List.of() : List.of(String.valueOf(content)));
  }

  private static CurrentContent completionContent(Map<String, Object> responseJson) {
    Object choicesValue = responseJson.get("choices");
    if (!(choicesValue instanceof List<?> choices) || choices.isEmpty() || !(choices.getFirst() instanceof Map<?, ?> choice)) {
      return new CurrentContent(null, List.of());
    }
    Object content = choice.get("text");
    return new CurrentContent(content, content == null ? List.of() : List.of(String.valueOf(content)));
  }

  private static CurrentContent anthropicMessagesContent(Map<String, Object> responseJson) {
    Object content = responseJson.get("content");
    if (!(content instanceof List<?> parts)) {
      return new CurrentContent(content, content == null ? List.of() : List.of(String.valueOf(content)));
    }
    List<String> textArray = parts.stream()
        .filter(Map.class::isInstance)
        .map(part -> stringParameter(((Map<?, ?>) part).get("text")))
        .toList();
    return new CurrentContent(content, textArray);
  }

  private static String contentType(Object content) {
    if (content == null) {
      return "null";
    }
    if (content instanceof String) {
      return "string";
    }
    if (content instanceof List<?>) {
      return "array";
    }
    if (content instanceof Map<?, ?>) {
      return "object";
    }
    if (content instanceof Number) {
      return "number";
    }
    if (content instanceof Boolean) {
      return "boolean";
    }
    return content.getClass().getSimpleName().toLowerCase(Locale.ROOT);
  }

  private static String countExplanation(
      String unit,
      int count,
      int min,
      int max,
      boolean not,
      boolean verdict) {
    if (verdict && not) {
      return "The text contains %d %s, which is outside the specified range of %d-%d %s as expected."
          .formatted(count, unit, min, max, unit);
    }
    if (verdict) {
      return "The text contains %d %s, which is within the specified range of %d-%d %s."
          .formatted(count, unit, min, max, unit);
    }
    if (not) {
      return "The text contains %d %s, which is within the specified range of %d-%d %s when it should not be."
          .formatted(count, unit, min, max, unit);
    }
    return "The text contains %d %s, which is outside the specified range of %d-%d %s."
        .formatted(count, unit, min, max, unit);
  }

  private static String sentenceExplanation(int count, int min, int max, boolean not, boolean verdict) {
    if (verdict && not) {
      return "The sentence count (%d) is outside the specified range of %d to %d as expected."
          .formatted(count, min, max);
    }
    if (verdict) {
      return "The sentence count (%d) is within the specified range of %d to %d."
          .formatted(count, min, max);
    }
    if (not) {
      return "The sentence count (%d) is within the specified range of %d to %d when it should not be."
          .formatted(count, min, max);
    }
    return "The sentence count (%d) is outside the specified range of %d to %d."
        .formatted(count, min, max);
  }

  private static String endsWithExplanation(String suffix, boolean not, boolean verdict, boolean trailingPeriodSuffix) {
    if (verdict && not) {
      return "The text does not end with \"%s\" as expected.".formatted(suffix);
    }
    if (verdict) {
      return "The text ends with \"%s\"%s."
          .formatted(suffix, trailingPeriodSuffix ? " (including trailing period)" : "");
    }
    if (not) {
      return "The text ends with \"%s\" when it should not.".formatted(suffix);
    }
    return "The text does not end with \"%s\".".formatted(suffix);
  }

  private static List<Map<String, Object>> extractedJsonObjects(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> candidates = new ArrayList<>();
    Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(text);
    while (codeBlockMatcher.find()) {
      candidates.add(codeBlockMatcher.group(1).trim());
    }
    candidates.addAll(bracedJsonCandidates(text));

    List<Map<String, Object>> objects = new ArrayList<>();
    for (String candidate : candidates) {
      try {
        objects.add(OBJECT_MAPPER.readValue(candidate, MAP_TYPE));
      } catch (JsonProcessingException ignored) {
        // Keep scanning; TS also skips invalid JSON snippets.
      }
    }
    return objects;
  }

  private static List<String> bracedJsonCandidates(String text) {
    List<String> candidates = new ArrayList<>();
    int depth = 0;
    int start = -1;
    boolean inString = false;
    boolean escaped = false;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (character == '\\' && inString) {
        escaped = true;
        continue;
      }
      if (character == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (character == '{') {
        if (depth == 0) {
          start = index;
        }
        depth++;
      } else if (character == '}' && depth > 0) {
        depth--;
        if (depth == 0 && start >= 0) {
          candidates.add(text.substring(start, index + 1));
          start = -1;
        }
      }
    }
    return candidates;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> objectMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return stringObjectMap(map);
    }
    return Map.of();
  }

  private static Map<String, Object> stringObjectMap(Map<?, ?> map) {
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(String.valueOf(key), JsonCopies.mutableDeepCopyValue(value)));
    return result;
  }

  private static List<String> stringListFlexible(Object value) {
    if (value instanceof String string) {
      if (string.isBlank()) {
        return List.of();
      }
      return java.util.Arrays.stream(string.split(","))
          .map(String::trim)
          .filter(item -> !item.isEmpty())
          .toList();
    }
    return stringList(value);
  }

  private static List<String> stringValues(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(String::valueOf).toList();
    }
    return List.of(String.valueOf(value));
  }

  private static boolean booleanParameter(Map<String, ?> parameters, String key) {
    Object value = parameters.get(key);
    return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
  }

  private static String stringParameter(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Integer integerParameter(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static Integer integerParameterOrNull(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static long longParameter(Object value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).toList();
  }
}
