package com.example.batch.common.health;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Citus 运行时前置自检(仅 {@code batch.citus.enabled=true} 时启用)。
 *
 * <p>背景:Citus 上有一类**配错不报错、只静默出错**的故障,普通 schema/连接自检看不出,必须启动期 {@code SHOW} GUC 拦截:
 *
 * <ul>
 *   <li><b>citus.propagate_set_commands</b> 必须 = {@code local}:否则 {@code SET LOCAL app.tenant_id}
 *       不透传到 worker 分片 → RLS 读**全空且不报错**(业务静默读到 0 行)。这是最危险的漏配,fail-fast 阻断启动。
 *   <li><b>citus.enable_unsafe_triggers</b> 必须 = {@code on}:否则带触发器的分布式表写入被拒。
 * </ul>
 *
 * <p>触发不存在(非 Citus 库却开了 {@code batch.citus.enabled})同样 fail-fast——避免"以为在 Citus 上"的错觉。
 *
 * <p>对照 {@code docs/backlog/citus-introduction-plan-2026-06-06.md} §0.5 / §7 与 {@code
 * docs/analysis/citus-poc-gates-2026-06-11.md} 门 1 的部署 checklist 要求。
 */
@Slf4j
public class CitusRuntimeStartupCheck {

  private static final String REQUIRED_PROPAGATE_SET_COMMANDS = "local";
  private static final String REQUIRED_ENABLE_UNSAFE_TRIGGERS = "on";

  private final ObjectProvider<DataSource> dataSourceProvider;

  public CitusRuntimeStartupCheck(ObjectProvider<DataSource> dataSourceProvider) {
    this.dataSourceProvider = dataSourceProvider;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.warn("batch.citus.enabled=true 但无可用 DataSource,跳过 Citus 运行时自检");
      return;
    }
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // 1) 确认确实是 Citus 库(开了开关却连普通 PG = 部署错配)
    Integer citusExt =
        jdbc.queryForObject(
            "select count(*) from pg_extension where extname = 'citus'", Integer.class);
    if (citusExt == null || citusExt == 0) {
      throw new IllegalStateException(
          "FATAL: batch.citus.enabled=true 但目标库未安装 citus 扩展。"
              + "确认连的是 Citus coordinator,或关闭 batch.citus.enabled。");
    }

    // 2) propagate_set_commands=local —— 漏配会让 RLS 读静默返回空,必须拦
    String propagate = showGuc(jdbc, "citus.propagate_set_commands");
    if (!REQUIRED_PROPAGATE_SET_COMMANDS.equalsIgnoreCase(propagate)) {
      throw new IllegalStateException(
          "FATAL: citus.propagate_set_commands="
              + propagate
              + " (要求 'local')。漏配会导致 SET LOCAL app.tenant_id 不透传 worker → RLS 读全空且不报错。"
              + "在 coordinator 执行 ALTER SYSTEM SET citus.propagate_set_commands='local' 并 reload。");
    }

    // 3) enable_unsafe_triggers=on —— 带触发器的分布式表需要
    String triggers = showGuc(jdbc, "citus.enable_unsafe_triggers");
    if (!REQUIRED_ENABLE_UNSAFE_TRIGGERS.equalsIgnoreCase(triggers)) {
      throw new IllegalStateException(
          "FATAL: citus.enable_unsafe_triggers="
              + triggers
              + " (要求 'on')。带触发器的分布式表写入会被拒。"
              + "在 coordinator 执行 ALTER SYSTEM SET citus.enable_unsafe_triggers='on' 并 reload。");
    }

    Integer distributed =
        jdbc.queryForObject(
            "select count(*) from pg_dist_partition", Integer.class); // Citus 元数据:已分布表数
    log.info(
        "Citus 运行时自检通过:propagate_set_commands={}, enable_unsafe_triggers={}, distributedTables={}",
        propagate,
        triggers,
        distributed);
  }

  /** {@code SHOW <guc>} 取单值;trim 后返回(可能为 null)。 */
  private String showGuc(JdbcTemplate jdbc, String guc) {
    return jdbc.queryForObject("SHOW " + guc, String.class);
  }
}
