package io.github.pinpols.batch.console.shared.security;

import java.util.Set;

/** 根据已认证身份解析动态控制台能力的共享扩展点。 */
public interface ConsoleCapabilityProvider {

  Set<String> resolveCapabilities(String username, Set<String> authorities);
}
