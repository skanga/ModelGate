package com.modelgate.gateway.provider;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProviderCatalog {
  private static final Map<String, String> DEFAULT_BASE_URLS = Map.ofEntries(
      Map.entry("openai", "https://api.openai.com"),
      Map.entry("anthropic", "https://api.anthropic.com/v1"),
      Map.entry("cohere", "https://api.cohere.ai"),
      Map.entry("mistral-ai", "https://api.mistral.ai/v1"),
      Map.entry("groq", "https://api.groq.com/openai/v1"),
      Map.entry("together-ai", "https://api.together.xyz"),
      Map.entry("deepseek", "https://api.deepseek.com"),
      Map.entry("x-ai", "https://api.x.ai/v1"),
      Map.entry("voyage", "https://api.voyageai.com/v1"),
      Map.entry("jina", "https://api.jina.ai/v1"),
      Map.entry("openrouter", "https://openrouter.ai/api"),
      Map.entry("perplexity-ai", "https://api.perplexity.ai"),
      Map.entry("stability-ai", "https://api.stability.ai"),
      Map.entry("replicate", "https://api.replicate.com/v1"),
      Map.entry("huggingface", "https://api-inference.huggingface.co"),
      Map.entry("anyscale", "https://api.endpoints.anyscale.com/v1"),
      Map.entry("cerebras", "https://api.cerebras.ai/v1"),
      Map.entry("deepinfra", "https://api.deepinfra.com/v1/openai"),
      Map.entry("deepbricks", "https://api.deepbricks.ai/v1"),
      Map.entry("novita-ai", "https://api.novita.ai/v3/openai"),
      Map.entry("lemonfox-ai", "https://api.lemonfox.ai/v1"),
      Map.entry("302ai", "https://api.302.ai"),
      Map.entry("ai21", "https://api.ai21.com/studio/v1"),
      Map.entry("aibadgr", "https://aibadgr.com/api/v1"),
      Map.entry("bytez", "https://api.bytez.com"),
      Map.entry("cometapi", "https://api.cometapi.com/v1"),
      Map.entry("dashscope", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
      Map.entry("featherless-ai", "https://api.featherless.ai/v1"),
      Map.entry("fireworks-ai", "https://api.fireworks.ai/inference/v1"),
      Map.entry("gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
      Map.entry("google", "https://generativelanguage.googleapis.com"),
      Map.entry("github", "https://models.inference.ai.azure.com"),
      Map.entry("hyperbolic", "https://api.hyperbolic.xyz"),
      Map.entry("inference-net", "https://api.inference.net/v1"),
      Map.entry("inception", "https://api.inceptionlabs.ai/v1"),
      Map.entry("iointelligence", "https://api.intelligence.io.solutions/api/v1"),
      Map.entry("kluster-ai", "https://api.kluster.ai/v1"),
      Map.entry("krutrim", "https://cloud.olakrutrim.com/v1"),
      Map.entry("lambda", "https://api.lambdalabs.com/v1"),
      Map.entry("lepton", "https://api.lepton.ai"),
      Map.entry("lingyi", "https://api.lingyiwanwu.com"),
      Map.entry("matterai", "https://api.matterai.so/v1"),
      Map.entry("modal", "https://api.modal.com/v1"),
      Map.entry("monsterapi", "https://llm.monsterapi.ai/v1"),
      Map.entry("moonshot", "https://api.moonshot.cn"),
      Map.entry("ncompass", "https://api.ncompass.tech/v1"),
      Map.entry("nebius", "https://api.studio.nebius.ai/v1"),
      Map.entry("nextbit", "https://api.nextbit256.com/v1"),
      Map.entry("nvidia", "https://integrate.api.nvidia.com"),
      Map.entry("nvidia-nim", "https://integrate.api.nvidia.com"),
      Map.entry("nomic", "https://api-atlas.nomic.ai/v1"),
      Map.entry("nscale", "https://inference.api.nscale.com/v1"),
      Map.entry("ovhcloud", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"),
      Map.entry("palm", "https://generativelanguage.googleapis.com/v1beta3"),
      Map.entry("predibase", "https://serving.app.predibase.com"),
      Map.entry("recraft-ai", "https://external.api.recraft.ai/v1"),
      Map.entry("reka-ai", "https://api.reka.ai"),
      Map.entry("sambanova", "https://api.sambanova.ai/v1"),
      Map.entry("segmind", "https://api.segmind.com/v1"),
      Map.entry("siliconflow", "https://api.siliconflow.cn/v1"),
      Map.entry("tripo3d", "https://api.tripo3d.ai/v2/openapi"),
      Map.entry("upstage", "https://api.upstage.ai/v1/solar"),
      Map.entry("z-ai", "https://api.z.ai/api/paas/v4"),
      Map.entry("zhipu", "https://open.bigmodel.cn/api/paas/v4"));

  private static final Set<String> SUPPORTED_PROVIDERS = Set.of(
      "openai",
      "cohere",
      "anthropic",
      "azure-openai",
      "huggingface",
      "anyscale",
      "palm",
      "together-ai",
      "google",
      "vertex-ai",
      "perplexity-ai",
      "mistral-ai",
      "deepinfra",
      "ncompass",
      "stability-ai",
      "nomic",
      "ollama",
      "ai21",
      "bedrock",
      "groq",
      "segmind",
      "jina",
      "fireworks-ai",
      "workers-ai",
      "reka-ai",
      "moonshot",
      "openrouter",
      "lingyi",
      "zhipu",
      "novita-ai",
      "monsterapi",
      "deepseek",
      "predibase",
      "triton",
      "voyage",
      "azure-ai",
      "github",
      "deepbricks",
      "siliconflow",
      "cerebras",
      "inference-net",
      "inception",
      "sambanova",
      "lemonfox-ai",
      "upstage",
      "lambda",
      "dashscope",
      "x-ai",
      "qdrant",
      "sagemaker",
      "nebius",
      "recraft-ai",
      "milvus",
      "replicate",
      "lepton",
      "kluster-ai",
      "nscale",
      "hyperbolic",
      "bytez",
      "featherless-ai",
      "krutrim",
      "gemini",
      "nvidia",
      "nvidia-nim",
      "302ai",
      "cometapi",
      "matterai",
      "meshy",
      "nextbit",
      "tripo3d",
      "modal",
      "z-ai",
      "oracle",
      "iointelligence",
      "aibadgr",
      "ovhcloud");

  private ProviderCatalog() {}

  public static String defaultBaseUrl(String provider) {
    String normalizedProvider = normalizeProvider(provider);
    String baseUrl = DEFAULT_BASE_URLS.get(normalizedProvider);
    if (baseUrl == null) {
      throw new IllegalArgumentException("No default base URL configured for provider: " + provider);
    }
    return baseUrl;
  }

  public static boolean hasDefaultBaseUrl(String provider) {
    return DEFAULT_BASE_URLS.containsKey(normalizeProvider(provider));
  }

  public static Set<String> supportedProviders() {
    return SUPPORTED_PROVIDERS;
  }

  private static String normalizeProvider(String provider) {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("No default base URL configured for provider: " + provider);
    }
    return provider.trim().toLowerCase(Locale.ROOT);
  }
}
