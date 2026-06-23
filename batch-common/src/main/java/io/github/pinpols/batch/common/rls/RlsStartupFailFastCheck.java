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
 * <p><b>必须 opt-in + 守门</b>:本 bean 只在 {@code batch.rls.startup-fail-fast=true} 时装配,且要求上下文里有 business
 * datasource(见 {@code @ConditionalOnBean})。这是踩过的坑 —— 副作用 bean 硬注入会牵连不装配该依赖的上下文启动失败。默认 false,只靠
 * health DOWN 可见,不阻断启动。
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
              + "coverage — set batch.rls.startup-fail-fast=false if biz库 RLS 未就绪. cause="
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
