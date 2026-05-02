package com.modelgate.gateway.plugins;

import java.util.Map;

public interface PluginRegistry {
  PluginResult execute(String pluginId, HookContext context, Map<String, ?> parameters);
}
