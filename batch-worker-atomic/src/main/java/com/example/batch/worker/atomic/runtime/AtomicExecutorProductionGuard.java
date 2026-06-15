package com.example.batch.worker.atomic.runtime;

import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import com.example.batch.worker.atomic.shell.ShellExecutorProperties;
import com.example.batch.worker.atomic.spark.SparkSubmitExecutorProperties;
import com.example.batch.worker.atomic.sql.SqlExecutorProperties;
import com.example.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Atomic worker 启动期 fail-closed 守护:prod profile 下校验 5 类 executor 配置不能"空 = 允许全部"。
 *
 * <p>背景:Sql/StoredProc/Http/Shell/SparkSubmit executor 的安全防护链都允许"空白名单 = 放行全部"以满足 dev 开发, 但生产同样配置等于把
 * dual-use RCE 接口对内全开,与 ADR-029 atomic worker 隔离初衷背离。
 *
 * <p>本守护只在 active profile 含 "prod" 时触发;dev/local/test/未声明 profile 完全放行,保持开发友好。
 *
 * <p>失败信息明确指向应配置的 yaml key,运维一眼能改。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AtomicExecutorProductionGuard {

  private static final String PROD_PROFILE = "prod";

  private final Environment environment;
  private final ObjectProvider<SqlExecutorProperties> sqlProps;
  private final ObjectProvider<StoredProcExecutorProperties> storedProcProps;
  private final ObjectProvider<HttpExecutorProperties> httpProps;
  private final ObjectProvider<ShellExecutorProperties> shellProps;
  private final ObjectProvider<SparkSubmitExecutorProperties> sparkProps;

  @EventListener(ApplicationReadyEvent.class)
  public void verifyProductionFailClosed() {
    if (!isProdProfile(environment.getActiveProfiles())) {
      log.debug(
          "atomic executor production guard skipped: activeProfiles={}",
          Arrays.toString(environment.getActiveProfiles()));
      return;
    }
    List<String> violations = collectViolations();
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "atomic worker prod profile fail-closed check failed:\n - "
              + String.join("\n - ", violations));
    }
    log.info("atomic executor production guard passed (prod profile)");
  }

  /** 包私有:供单测直接喂 mock properties 触发同样逻辑,不必拉 Spring context。 */
  List<String> collectViolations() {
    List<String> violations = new ArrayList<>();
    SqlExecutorProperties sql = sqlProps.getIfAvailable();
    if (sql != null && sql.isEnabled() && sql.getAllowedDataSourceBeans().isEmpty()) {
      violations.add(
          "batch.worker.executors.sql.enabled=true 但 allowedDataSourceBeans 为空"
              + " — prod profile 必须显式配 batch.worker.executors.sql.allowed-data-source-beans"
              + " 列出可用 DataSource bean 名(防业务方借 parameters.dataSourceBean 切到任意高权限连接)");
    }
    StoredProcExecutorProperties sp = storedProcProps.getIfAvailable();
    if (sp != null && sp.isEnabled() && sp.getAllowedSchemas().isEmpty()) {
      violations.add(
          "batch.worker.executors.stored-proc.enabled=true 但 allowedSchemas 为空"
              + " — prod profile 必须配 batch.worker.executors.stored-proc.allowed-schemas"
              + " 限定可调用的 schema(默认空=允许全部,prod 风险过大)");
    }
    HttpExecutorProperties http = httpProps.getIfAvailable();
    if (http != null
        && http.isEnabled()
        && http.getAllowedHostPatterns().isEmpty()
        && !http.isEnforceAllowlist()) {
      violations.add(
          "batch.worker.executors.http.enabled=true 但 allowedHostPatterns 为空且"
              + " enforceAllowlist=false — prod profile 必须二选一:"
              + "(a) 配 batch.worker.executors.http.allowed-host-patterns 列出可访问的出口域名 glob;"
              + "(b) 置 batch.worker.executors.http.enforce-allowlist=true 让空白名单语义变成"
              + " fail-closed 拒绝全部");
    }
    ShellExecutorProperties shell = shellProps.getIfAvailable();
    if (shell != null && shell.isEnabled() && shell.getCommandWhitelist().isEmpty()) {
      violations.add(
          "batch.worker.executors.shell.enabled=true 但 commandWhitelist 为空"
              + " — prod profile 必须配 batch.worker.executors.shell.command-whitelist"
              + " 列出允许执行的程序绝对路径(默认空=允许全部 program,任意 RCE 风险)");
    }
    SparkSubmitExecutorProperties spark = sparkProps.getIfAvailable();
    if (spark != null && spark.isEnabled() && spark.getAppResourceAllowlist().isEmpty()) {
      violations.add(
          "batch.worker.executors.spark-submit.enabled=true 但 appResourceAllowlist 为空"
              + " — prod profile 必须配 batch.worker.executors.spark-submit.app-resource-allowlist"
              + " 列出允许提交的 jar/.py 前缀(默认空=允许提交任意 jar,等同任意代码执行 RCE)");
    }
    return violations;
  }

  private static boolean isProdProfile(String[] activeProfiles) {
    if (activeProfiles == null) {
      return false;
    }
    for (String p : activeProfiles) {
      if (p != null && PROD_PROFILE.equalsIgnoreCase(p.trim())) {
        return true;
      }
      // 同时允许常见复合命名,如 "prod-eu"、"prod_us":只要任一片段匹配 prod
      if (p != null) {
        for (String token : p.toLowerCase(Locale.ROOT).split("[\\-_,]")) {
          if (PROD_PROFILE.equals(token)) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
