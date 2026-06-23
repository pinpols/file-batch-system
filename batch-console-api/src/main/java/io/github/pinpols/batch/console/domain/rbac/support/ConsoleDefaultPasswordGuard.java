package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.config.BatchProfileSupport;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 部署期默认密码守护:启动后检测内置系统账号(admin / auditor / config-admin)的 password_hash 是否仍是出厂明文 {@code admin123}。
 *
 * <p>命中策略(对齐 {@code ConsoleJwtService} prod fail-fast 范式):
 *
 * <ul>
 *   <li><b>prod profile</b>:仍用出厂弱口令 → {@link IllegalStateException} fail-fast,拒绝启动 ——防止出厂密码被带上生产。
 *   <li><b>非 prod</b>:仅打 WARN(本地 / 联调要能起),提示尽快改密;同时若该账号 must_change_password 已为 false(改过密),则不告警。
 * </ul>
 *
 * <p>Argon2id 含随机盐,出厂 hash 字面量每次 encode 都不同,故用 {@code passwordHasher.matches("admin123", hash)}
 * 比对而非比字符串。
 */
@Slf4j
@Component
public class ConsoleDefaultPasswordGuard {

  /** 出厂内置账号(V52 seed),与 V174 标记 must_change_password=true 的集合一致。 */
  static final List<String> BUILTIN_USERNAMES = List.of("admin", "auditor", "config-admin");

  /** V52 / 文档公示的出厂明文密码。 */
  static final String FACTORY_DEFAULT_PASSWORD = "admin123";

  private final ConsoleUserAccountMapper userAccountMapper;
  private final ConsolePasswordHasher passwordHasher;
  private final Environment environment;

  public ConsoleDefaultPasswordGuard(
      ConsoleUserAccountMapper userAccountMapper,
      ConsolePasswordHasher passwordHasher,
      Environment environment) {
    this.userAccountMapper = userAccountMapper;
    this.passwordHasher = passwordHasher;
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void checkBuiltinDefaultPasswords() {
    List<ConsoleUserAccountEntity> accounts =
        userAccountMapper.selectBuiltinSystemAccounts(BUILTIN_USERNAMES);
    List<String> stillDefault =
        accounts.stream()
            .filter(a -> usesFactoryDefault(a.getPasswordHash()))
            .map(ConsoleUserAccountEntity::getUsername)
            .collect(Collectors.toList());
    if (stillDefault.isEmpty()) {
      return;
    }
    boolean productionMode = BatchProfileSupport.isProductionProfile(environment);
    if (productionMode) {
      throw new IllegalStateException(
          "FATAL: 内置控制台账号 "
              + stillDefault
              + " 仍使用出厂默认密码 admin123,生产环境拒绝启动;请先经 reset-password / change-password 改密后再部署");
    }
    log.warn(
        "⚠️ 非生产 profile:内置控制台账号 {} 仍使用出厂默认密码 admin123,首次登录会被强制改密;"
            + "生产部署前务必改密(prod profile 下会 fail-fast 拒绝启动)",
        stillDefault);
  }

  private boolean usesFactoryDefault(String passwordHash) {
    if (passwordHash == null || passwordHash.isBlank()) {
      return false;
    }
    try {
      return passwordHasher.matches(FACTORY_DEFAULT_PASSWORD, passwordHash);
    } catch (RuntimeException ignored) {
      // 非 Argon2 / 异常 hash 不视为出厂默认,交由其他校验处理。
      return false;
    }
  }
}
