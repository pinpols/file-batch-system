package com.example.batch.worker.atomic.runtime;

import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import com.example.batch.worker.atomic.shell.ShellExecutorProperties;
import com.example.batch.worker.atomic.sql.SqlExecutorProperties;
import com.example.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * ADR-029 dual-use(RCE 级)SPI executor 启动期隔离守护。
 *
 * <p><b>背景</b>:batch-worker-atomic 同一个镜像可按 {@code batch.worker.executors.*} 开关启用 shell / sql /
 * stored-proc / http 等 executor。其中 shell + sql + stored-proc 属于 dual-use(可在 worker 进程里执行任意命令 / 任意
 * SQL / 任意存储过程),一旦在「连业务库 / 高权限 SA / 出口无限制」的 pod 上启用,等于把 RCE 能力直接挂进生产网络。ADR-029 要求这类 worker
 * 部署必须满足最小权限:
 *
 * <ul>
 *   <li>独立低权限 DB role(只给 claim/lease 平台库,绝不连业务库)
 *   <li>独立 ServiceAccount(最小 IAM,不复用平台共享 SA)
 *   <li>出口 NetworkPolicy(default-deny egress,只放行 DNS / 平台库 / Kafka / 显式 SPI 目标)
 *   <li>独立受限 secret(不含业务库 / MinIO 凭据)
 * </ul>
 *
 * <p><b>守护逻辑</b>:{@link ApplicationReadyEvent} 触发,检测是否有任一 dual-use executor 启用。
 *
 * <ul>
 *   <li>若启用 ≥1 个:打一条醒目 WARN,提醒 ADR-029 最小权限要求。
 *   <li>若 {@code batch.worker.spi.require-isolation=true} 且启用了 dual-use executor 且 {@code
 *       batch.worker.spi.isolation-acknowledged} 不为 true:fail-fast 抛 {@link IllegalStateException}。
 *       这给生产一个「强制 ops 显式 ack 已完成隔离部署」的闸门。
 * </ul>
 *
 * <p><b>为何用 {@link Environment} 读 require-isolation / isolation-acknowledged 而非新增
 * {@code @ConfigurationProperties}</b>:{@code batch.worker.spi} 前缀已被 {@code
 * AtomicWorkerConfiguration} (record)与 {@code BatchWorkerAtomicProperties} 绑定。再加一个绑同前缀的 properties
 * 类会与现有 record 产生构造绑定冲突 (record 强类型构造无法容忍未知子键的部分绑定语义),因此本类直接用 {@code Environment.getProperty} 读两个
 * boolean flag,零绑定侵入。
 *
 * <p>executor properties 用 {@link ObjectProvider} 注入 —— 它们各自 {@code @EnableConfigurationProperties}
 * 注册,可能存在也可能缺失;即便 bean 存在,仍以 {@code isEnabled()} 为准判断是否真正启用(@ConditionalOnProperty 控制的是 executor
 * 自身,properties bean 可能恒在)。
 */
@Slf4j
@Component
public class AtomicIsolationStartupCheck {

  static final String PROP_REQUIRE_ISOLATION = "batch.worker.atomic.require-isolation";
  static final String PROP_ISOLATION_ACKNOWLEDGED = "batch.worker.atomic.isolation-acknowledged";

  private final ObjectProvider<ShellExecutorProperties> shellProps;
  private final ObjectProvider<SqlExecutorProperties> sqlProps;
  private final ObjectProvider<StoredProcExecutorProperties> storedProcProps;
  private final ObjectProvider<HttpExecutorProperties> httpProps;
  private final Environment environment;

  public AtomicIsolationStartupCheck(
      ObjectProvider<ShellExecutorProperties> shellProps,
      ObjectProvider<SqlExecutorProperties> sqlProps,
      ObjectProvider<StoredProcExecutorProperties> storedProcProps,
      ObjectProvider<HttpExecutorProperties> httpProps,
      Environment environment) {
    this.shellProps = shellProps;
    this.sqlProps = sqlProps;
    this.storedProcProps = storedProcProps;
    this.httpProps = httpProps;
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    List<String> enabledDualUse = enabledDualUseExecutors();
    if (enabledDualUse.isEmpty()) {
      log.debug("SPI isolation check: no dual-use executor enabled, nothing to enforce");
      return;
    }

    log.warn(
        "===== ADR-029 dual-use SPI executor(s) ENABLED: {} ===== "
            + "This worker can run arbitrary commands/SQL/stored-procs. "
            + "MINIMAL-PRIVILEGE deployment is REQUIRED: "
            + "(1) 独立低权限 DB role —— 只给平台库 claim/lease,绝不连业务库; "
            + "(2) 独立 ServiceAccount —— 最小 IAM,不复用平台共享 SA; "
            + "(3) 出口 NetworkPolicy —— default-deny egress,仅放行 DNS/平台库/Kafka/显式 SPI 目标; "
            + "(4) 独立受限 secret —— 不含业务库/MinIO 凭据。 "
            + "Set {}=true after isolation is verified to silence the fail-fast gate.",
        enabledDualUse,
        PROP_ISOLATION_ACKNOWLEDGED);

    boolean requireIsolation =
        environment.getProperty(PROP_REQUIRE_ISOLATION, Boolean.class, false);
    boolean acknowledged =
        environment.getProperty(PROP_ISOLATION_ACKNOWLEDGED, Boolean.class, false);

    if (requireIsolation && !acknowledged) {
      throw new IllegalStateException(
          "ADR-029 isolation gate: dual-use SPI executor(s) "
              + enabledDualUse
              + " enabled with "
              + PROP_REQUIRE_ISOLATION
              + "=true but "
              + PROP_ISOLATION_ACKNOWLEDGED
              + " not set. Refusing to start. Verify minimal-privilege deployment "
              + "(dedicated low-priv DB role / dedicated ServiceAccount / egress NetworkPolicy / "
              + "restricted secret) then set "
              + PROP_ISOLATION_ACKNOWLEDGED
              + "=true.");
    }
  }

  /** dual-use(RCE 级)executor 列表:shell / sql / stored-proc。http 不算 dual-use(受域名白名单约束)。 */
  private List<String> enabledDualUseExecutors() {
    List<String> enabled = new ArrayList<>();
    ShellExecutorProperties shell = shellProps.getIfAvailable();
    if (shell != null && shell.isEnabled()) {
      enabled.add("shell");
    }
    SqlExecutorProperties sql = sqlProps.getIfAvailable();
    if (sql != null && sql.isEnabled()) {
      enabled.add("sql");
    }
    StoredProcExecutorProperties storedProc = storedProcProps.getIfAvailable();
    if (storedProc != null && storedProc.isEnabled()) {
      enabled.add("stored-proc");
    }
    // http executor 受出口域名白名单约束,不归为 dual-use RCE;仅在有它启用时也提示隔离,
    // 但它本身不触发 fail-fast(仍计入 WARN 的整体上下文判断之外)。这里有意不加入 enabled。
    HttpExecutorProperties http = httpProps.getIfAvailable();
    if (http != null && http.isEnabled()) {
      log.debug("SPI http executor enabled (egress-restricted, not gated as dual-use RCE)");
    }
    return enabled;
  }
}
