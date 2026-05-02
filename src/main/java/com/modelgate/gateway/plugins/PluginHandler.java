package com.modelgate.gateway.plugins;

import java.util.Map;

@FunctionalInterface
interface PluginHandler {
  PluginResult execute(HookContext context, Map<String, ?> parameters);
}
