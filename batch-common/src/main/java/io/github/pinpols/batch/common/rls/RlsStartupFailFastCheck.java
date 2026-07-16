package io.github.pinpols.batch.common.rls;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Phase A · RLS 启动期 fail-fast 守门(opt-in)。
 *
 * <p>{@link ApplicationReadyEvent} 触发时跑与 {@link RlsPolicyHealthIndicator} <b>同一套</b>闭世界检查({@link
 * RlsClosedWorldChecker});任何 biz 表缺 RLS(ENABLE/FORCE/policy)→ 抛 {@link IllegalStateException} 拒绝启动。
 *
 * <p>本 bean 由 {@code batch.rls.startup-fail-fast} 控制；该配置默认开启。没有业务数据源的上下文不应装配此 bean，避免把 RLS
 * 守门错误地施加到只使用平台库的模块。
 *
 * <p>biz 库不可达(SQLException)时同样 fail-fast:开了开关就表示该部署必须 RLS 就绪,连不上也不该静默放行。
 */
@Slf4j
public class RlsStartupFailFastCheck {

  private final RlsClosedWorldChecker checker;

  public RlsStartupFailFastCheck(DataSource businessDataSource, List<String> exemptTables) {
    this.checker = new RlsClosedWorldChecker(businessDataSource, exemptTables);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    RlsClosedWorldChecker.Result result;
    try {
      result = checker.check();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "RLS startup fail-fast: biz datasource unreachable while verifying closed-world RLS "
              + "coverage — configure the business database and RLS before starting. cause="
              + e.getMessage(),
          e);
    }
    if (!result.isClean()) {
      log.error(
          "RLS closed-world check failed: missingEnable={} missingForce={} missingPolicy={}",
          result.missingEnable(),
          result.missingForce(),
          result.missingPolicy());
      throw new IllegalStateException(
          "RLS startup fail-fast: "
              + result.allMissingTables().size()
              + " biz table(s) missing RLS enable/force/policy: "
              + result.allMissingTables()
              + ". Run scripts/db/business/rls-phase-a.sql or add the table to "
              + "batch.rls.exempt-tables if it is non-tenant metadata. "
              + "See logs above for per-category diff.");
    }
    log.info("RLS closed-world startup check passed");
  }
}
