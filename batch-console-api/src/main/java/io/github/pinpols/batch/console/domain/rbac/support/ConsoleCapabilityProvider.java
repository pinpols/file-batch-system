package io.github.pinpols.batch.console.domain.rbac.support;

import java.util.Set;

/** 根据当前身份返回已开通的控制台能力。 */
@FunctionalInterface
public interface ConsoleCapabilityProvider {

  Set<String> resolveCapabilities(String username, Set<String> authorities);
}
