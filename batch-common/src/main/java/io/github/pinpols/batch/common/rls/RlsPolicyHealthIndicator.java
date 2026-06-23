package io.github.pinpols.batch.common.rls;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Phase A · RLS healthcheck — 启动期 + actuator/health 时<b>闭世界</b>确认真实 biz.* 表(+
 * batch.process_staging) 都启用了 RLS(ENABLE + FORCE)且挂了 tenant_isolation policy。
 *
 * <p><b>闭世界(closed-world)</b>:不再遍历硬编码清单,而是扫真实 biz schema 的表(排除分区子表)。新增 biz 表漏配 RLS 时也会被发现 —— 旧的硬编码
 * 清单做不到(不在清单里就静默放过 → 跨租户泄露)。检查逻辑见 {@link RlsClosedWorldChecker}。
 *
 * <p>缺一报 DOWN,details 分 {@code missingEnableRls}/{@code missingForceRls}/{@code missingPolicy}
 * 列出缺哪张表 —— 让平台运维加新 biz 表时漏配 RLS 立刻可见。biz 库不可达保持 try-catch 转 DOWN,不 crash。
 */
@Slf4j
public class RlsPolicyHealthIndicator implements HealthIndicator {

  /** 翻转 transition → strict 期间,健康检查接受任一 policy 名(灰度兼容)。保留作向后引用。 */
  public static final List<String> ACCEPTED_POLICY_NAMES =
      RlsClosedWorldChecker.ACCEPTED_POLICY_NAMES;

  private final RlsClosedWorldChecker checker;

  /** 兼容旧构造器:无豁免清单。 */
  public RlsPolicyHealthIndicator(DataSource businessDataSource) {
    this(businessDataSource, List.of());
  }

  public RlsPolicyHealthIndicator(DataSource businessDataSource, List<String> exemptBizTables) {
    this.checker = new RlsClosedWorldChecker(businessDataSource, exemptBizTables);
  }

  @Override
  public Health health() {
    RlsClosedWorldChecker.Result result;
    try {
      result = checker.check();
    } catch (SQLException e) {
      log.warn("RLS health check failed: {}", e.getMessage());
      return Health.down().withException(e).build();
    }

    Health.Builder builder = result.isClean() ? Health.up() : Health.down();
    if (!result.missingEnable().isEmpty()) {
      builder.withDetail("missingEnableRls", result.missingEnable());
    }
    if (!result.missingForce().isEmpty()) {
      builder.withDetail("missingForceRls", result.missingForce());
    }
    if (!result.missingPolicy().isEmpty()) {
      builder.withDetail("missingPolicy", result.missingPolicy());
    }
    if (!result.isClean()) {
      builder.withDetail("missingRlsTables", result.allMissingTables());
    }
    return builder.build();
  }
}
