package com.modelgate.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderCatalogTest {
  @Test
  void supportsProvidersExposedByTypeScriptProviderRegistry() {
    assertThat(ProviderCatalog.supportedProviders())
        .containsAll(List.of(
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
            "ovhcloud"));
  }

  @Test
  void supportsInitialHighPerformanceProviderSet() {
    assertThat(ProviderCatalog.supportedProviders())
        .containsAll(List.of(
            "openai",
            "anthropic",
            "gemini",
            "groq",
            "nvidia-nim",
            "nvidia",
            "sambanova",
            "cerebras",
            "inception"));
  }
}
