package io.github.pinpols.batch.console.domain.rbac.support;

import java.time.Duration;

/** Console JWT 撤销名单存储端口。 */
public interface ConsoleTokenRevocationStore {

  boolean isRevoked(String jti);

  void revoke(String jti, Duration ttl);
}
