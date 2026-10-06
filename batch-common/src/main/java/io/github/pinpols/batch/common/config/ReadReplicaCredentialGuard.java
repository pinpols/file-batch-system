package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;

/**
 * Console 主 / 从库密码启动守护：生产 profile 下拒绝已知弱默认口令，非生产 profile 显式 WARN。
 *
 * <p>该职责原先内联在 {@code BatchSecurityProperties} 中，通过 {@code environment.getProperty} 直接读取
 * {@code batch.console.read-replica.*.password}。由于 batch-common 无法引用 console-api 的
 * {@code ReadReplicaProperties}，这里改为读取 {@link ConsoleReadReplicaProperties} 只读视图，统一 key 口径并消除
 * 散落的字符串 key 读取。非 console 模块该前缀不存在，密码为 {@code null}，守护自动跳过。
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ConsoleReadReplicaProperties.class)
public class ReadReplicaCredentialGuard implements SmartInitializingSingleton {

  /** prod 库连接默认弱口令清单——出现在 application.yml 默认值里，绝不能进生产。 */
  private static final Set<String> KNOWN_WEAK_DB_PASSWORDS = Set.of("batch_pass_123");

  private static final String PRIMARY_PASSWORD_KEY = "batch.console.read-replica.primary.password";

  private static final String REPLICA_PASSWORD_KEY = "batch.console.read-replica.replica.password";

  private final ConsoleReadReplicaProperties readReplicaProperties;
  private final Environment environment;

  public ReadReplicaCredentialGuard(
      ConsoleReadReplicaProperties readReplicaProperties, Environment environment) {
    this.readReplicaProperties = readReplicaProperties;
    this.environment = environment;
  }

  @Override
  public void afterSingletonsInstantiated() {
    String primaryPassword = readReplicaProperties.getPrimary().getPassword();
    String replicaPassword = readReplicaProperties.getReplica().getPassword();
    if (BatchProfileSupport.isProductionProfile(environment)) {
      validateNotKnownWeakDbPassword(PRIMARY_PASSWORD_KEY, primaryPassword);
      validateNotKnownWeakDbPassword(REPLICA_PASSWORD_KEY, replicaPassword);
      return;
    }
    // 非 prod：不 fail-fast（本地 / 联调要能起），但把“弱默认口令仍在用”显式 WARN 出来，
    // 兜 prod fail-fast 的第二层，防“漏开 prod profile 就静默用默认密码连真库”（审计 #4）。
    warnIfKnownWeakDbPassword(PRIMARY_PASSWORD_KEY, primaryPassword);
    warnIfKnownWeakDbPassword(REPLICA_PASSWORD_KEY, replicaPassword);
  }

  /** 非 prod：DB 密码仍为已知弱默认口令 → WARN（不阻断，property 不存在的模块跳过）。 */
  private void warnIfKnownWeakDbPassword(String key, String value) {
    if (EmptyChecks.isNotNull(value) && KNOWN_WEAK_DB_PASSWORDS.contains(value.trim())) {
      log.warn(
          "Non-production profile: {} still uses the shipped weak password; inject real credentials before production"
              + " (production-like profiles fail fast)",
          key);
    }
  }

  /** 仅当 property 实际存在（非 null）且命中已知弱默认口令时 fail-fast；property 不存在的模块跳过。 */
  private void validateNotKnownWeakDbPassword(String key, String value) {
    if (EmptyChecks.isNull(value)) {
      return;
    }
    if (KNOWN_WEAK_DB_PASSWORDS.contains(value.trim())) {
      throw new IllegalStateException(
          "FATAL: production database password " + key
              + " still uses a known weak password; inject real credentials through a secret manager or environment variable");
    }
  }
}
