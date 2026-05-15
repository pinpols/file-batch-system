package com.example.batch.orchestrator.infrastructure.archive;

import com.example.batch.common.mapper.InformationSchemaMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期校验 batch 热表与 archive 冷表的 column 列表一致性,差异即 fail-fast。
 *
 * <p><b>背景</b>:V71 用 {@code LIKE batch.<x> INCLUDING ALL} 一次性复制热表结构到 {@code
 * archive.<x>_archive}。后续给热表加列(V72/V73...),冷表不会自动同步。当 {@code OutboxArchiveService.archive()} /
 * {@code SuccessInstanceArchiveService.archive()} 走 {@code INSERT ... SELECT *} 路径时,列数不一致会让 INSERT
 * 失败,archive scheduler 整个挂掉, 热表归档停止 → 单表无限增长(比 archive 不存在更危险)。
 *
 * <p><b>守护逻辑</b>:启动期 {@link ApplicationReadyEvent} 触发,对比 14 张归档对照表的 column 集合。差异即 throw →
 * orchestrator 启动失败,强制开发者下次 ALTER 热表前同步 给 archive 加 migration。
 *
 * <p>无条件启用 — 本身资源占用可忽略(ApplicationReady 一次性 14 个 information_schema 查询), archive 表不存在(归档功能未启用 / V71
 * migration 未跑)时 {@link #checkOnStartup()} 自动 skip。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveSchemaDriftCheck {

  /**
   * 归档对照表清单 — V71 起步 14 张 + 后续 ADR 增量加表（如 V108 新增 result_version）。
   *
   * <p>每加一张归档表必须同步在这里登记，否则 archive 列差异不被守护。
   */
  static final List<String> ARCHIVED_TABLES =
      List.of(
          "outbox_event",
          "event_delivery_log",
          "event_outbox_retry",
          "job_instance",
          "job_partition",
          "job_task",
          "job_step_instance",
          "pipeline_instance",
          "pipeline_step_run",
          "file_dispatch_record",
          "workflow_run",
          "workflow_node_run",
          "job_execution_log",
          "compensation_command",
          // V108 (ADR-017) — result_version 主模型；archive 镜像由 LIKE INCLUDING ALL 创建
          "result_version",
          // V110 (ADR-020) — batch_day_replay 聚合 + 子项；archive 镜像同样 LIKE INCLUDING ALL
          "batch_day_replay_session",
          "batch_day_replay_entry",
          // R7-A3-P0 / V118 (ADR-021) — 数据质量规则 + 校验记录；archive 镜像已建立但之前未注册，
          // 任何加列 migration 启动期不会 fail-fast，归档静默漂移。
          "data_quality_rule",
          "data_quality_check");

  private final InformationSchemaMapper informationSchemaMapper;

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    int driftCount = 0;
    for (String hot : ARCHIVED_TABLES) {
      Set<String> hotCols = columnsOf("batch", hot);
      Set<String> coldCols = columnsOf("archive", hot + "_archive");
      // archive 表如果根本不存在(归档功能未启用 + V71 migration 没跑),hotCols 也 empty 时一起 skip
      if (hotCols.isEmpty() || coldCols.isEmpty()) {
        log.info(
            "archive schema drift check skipped for {}: hot={} cold={} (table missing)",
            hot,
            hotCols.size(),
            coldCols.size());
        continue;
      }
      Set<String> missingInCold = new TreeSet<>(hotCols);
      missingInCold.removeAll(coldCols);
      Set<String> missingInHot = new TreeSet<>(coldCols);
      missingInHot.removeAll(hotCols);
      if (!missingInCold.isEmpty() || !missingInHot.isEmpty()) {
        driftCount++;
        log.error(
            "archive schema drift on {}: hot has extra {} | cold has extra {}",
            hot,
            missingInCold,
            missingInHot);
      }
    }
    if (driftCount > 0) {
      throw new IllegalStateException(
          "archive schema drift detected on "
              + driftCount
              + " table(s). "
              + "Add migration to ALTER archive.* schema to match batch.* before next deploy. "
              + "See logs above for column-level diff.");
    }
    log.info("archive schema drift check passed for {} tables", ARCHIVED_TABLES.size());
  }

  /** Public 让 IT 测试直接调验证查询行为;运行期由 {@link #checkOnStartup()} 内部使用。 */
  public Set<String> columnsOf(String schema, String table) {
    return new HashSet<>(informationSchemaMapper.listColumns(schema, table));
  }
}
