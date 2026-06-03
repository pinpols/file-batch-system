package com.example.batch.orchestrator.infrastructure.archive;

import com.example.batch.common.mapper.InformationSchemaMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
          "data_quality_check",
          // V139 (DBA-2026-05-20 P0-3) — trigger_outbox_event 归档表;ADR-010 trigger 异步事件。
          "trigger_outbox_event",
          // V140 (DBA-2026-05-20 P0-4) — dead_letter_task 归档表;死信任务事故复盘。
          "dead_letter_task",
          // V159 (SDK Phase 3 M3.1) — custom_task_type_registry 归档表;租户自定义 taskType 注册。
          "custom_task_type_registry",
          // V164 (ADR-038 P1) — pipeline_progress 归档表;平台 worker LOAD/GENERATE 续跑位点。
          "pipeline_progress",
          // V165 (Round-1 TOP-8 / R3-5) — atomic_task_config 归档表;租户保存的 atomic 节点配置。
          "atomic_task_config");

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
    return Set.copyOf(informationSchemaMapper.listColumns(schema, table));
  }

  /**
   * 列级类型 / nullable 漂移检测 — 独立于 {@link #checkOnStartup()}。
   *
   * <p>{@link #checkOnStartup()} 只看列名集合,无法捕获 {@code ALTER COLUMN tenant_id TYPE varchar(128)} /
   * {@code ALTER COLUMN xxx SET NOT NULL} 这类语义变更:列名集合仍然相等,但 archive INSERT 会因类型 截断 / NULL
   * 违反而失败。本方法对每张 {@link #ARCHIVED_TABLES} 比对核心兼容性:
   *
   * <ul>
   *   <li>{@code data_type} 必须严格相等 — 类型不一致 INSERT-SELECT 直接失败 / 截断
   *   <li>{@code is_nullable} 只在「冷比热严格」时报错(cold=NO + hot=YES):热表允许 NULL 但冷表拒,归档 INSERT 会 NULL 违反 —
   *       反向(hot NOT NULL + cold NULLABLE)是 V91 起的故意设计(archive 是冷库,不强制 NOT NULL,见 V91 comment),不报错。
   *   <li>{@code column_default} 不参与比对 — INSERT-SELECT 从热表带值,default 不影响行内容。
   * </ul>
   *
   * <p>仅对冷热两侧都存在的列做对比 — 列名集合差异由 {@link #checkOnStartup()} 负责报错,避免重复噪音。
   */
  @EventListener(ApplicationReadyEvent.class)
  public void checkColumnTypesOnStartup() {
    List<String> diffs = new ArrayList<>();
    for (String hot : ARCHIVED_TABLES) {
      Map<String, ColumnMeta> hotMeta = columnMetaOf("batch", hot);
      Map<String, ColumnMeta> coldMeta = columnMetaOf("archive", hot + "_archive");
      if (hotMeta.isEmpty() || coldMeta.isEmpty()) {
        // 与 checkOnStartup 行为一致:任一侧表缺失,skip(列名集合检查会兜底)
        continue;
      }
      Set<String> shared = new TreeSet<>(hotMeta.keySet());
      shared.retainAll(coldMeta.keySet());
      for (String col : shared) {
        ColumnMeta h = hotMeta.get(col);
        ColumnMeta c = coldMeta.get(col);
        if (!Objects.equals(h.dataType(), c.dataType())) {
          diffs.add(hot + "." + col + " data_type hot=" + h.dataType() + " cold=" + c.dataType());
        }
        // varchar(N) / numeric(P,S) 的长度/精度差异在 character_maximum_length / numeric_*,
        // data_type 不带,必须显式比对
        if (!Objects.equals(h.characterMaximumLength(), c.characterMaximumLength())) {
          diffs.add(
              hot
                  + "."
                  + col
                  + " character_maximum_length hot="
                  + h.characterMaximumLength()
                  + " cold="
                  + c.characterMaximumLength());
        }
        if (!Objects.equals(h.numericPrecision(), c.numericPrecision())
            || !Objects.equals(h.numericScale(), c.numericScale())) {
          diffs.add(
              hot
                  + "."
                  + col
                  + " numeric precision/scale hot="
                  + h.numericPrecision()
                  + "/"
                  + h.numericScale()
                  + " cold="
                  + c.numericPrecision()
                  + "/"
                  + c.numericScale());
        }
        // 只报 "cold 比 hot 严格" 方向 — cold NOT NULL + hot NULLABLE 会让 NULL 行 INSERT 失败
        if ("NO".equalsIgnoreCase(c.isNullable()) && "YES".equalsIgnoreCase(h.isNullable())) {
          diffs.add(
              hot
                  + "."
                  + col
                  + " is_nullable hot=YES cold=NO (cold rejects NULL rows hot may produce)");
        }
      }
    }
    if (!diffs.isEmpty()) {
      for (String d : diffs) {
        log.error("archive column type drift: {}", d);
      }
      throw new IllegalStateException(
          "archive column type drift detected on "
              + diffs.size()
              + " column(s). "
              + "Hot vs cold (data_type or restrictive is_nullable) mismatch — "
              + "add migration to ALTER archive.* to match batch.* before next deploy. "
              + "See logs above for per-column diff.");
    }
    log.info("archive column type drift check passed for {} tables", ARCHIVED_TABLES.size());
  }

  private Map<String, ColumnMeta> columnMetaOf(String schema, String table) {
    List<Map<String, Object>> rows = informationSchemaMapper.listColumnsWithTypes(schema, table);
    Map<String, ColumnMeta> map = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      // PostgreSQL JDBC 通常以全小写键返回 information_schema 字段;某些驱动会大写,加防御。
      String name = asString(row.get("column_name"));
      if (name == null) {
        name = asString(row.get("COLUMN_NAME"));
      }
      String dataType = firstNonNull(row.get("data_type"), row.get("DATA_TYPE"));
      String isNullable = firstNonNull(row.get("is_nullable"), row.get("IS_NULLABLE"));
      String columnDefault = firstNonNull(row.get("column_default"), row.get("COLUMN_DEFAULT"));
      // P0 修复: PostgreSQL `data_type` 对 varchar/numeric 不带长度/精度
      // (varchar(64) 与 varchar(128) 同样返回 'character varying'),长度差别在
      // `character_maximum_length` / `numeric_precision` / `numeric_scale`,
      // 必须显式比对,否则 ALTER COLUMN ... TYPE varchar(N) 的漂移会漏检。
      String charMaxLen =
          firstNonNull(row.get("character_maximum_length"), row.get("CHARACTER_MAXIMUM_LENGTH"));
      String numPrec = firstNonNull(row.get("numeric_precision"), row.get("NUMERIC_PRECISION"));
      String numScale = firstNonNull(row.get("numeric_scale"), row.get("NUMERIC_SCALE"));
      if (name != null) {
        map.put(
            name,
            new ColumnMeta(dataType, isNullable, columnDefault, charMaxLen, numPrec, numScale));
      }
    }
    return map;
  }

  private static String asString(Object v) {
    return v == null ? null : v.toString();
  }

  private static String firstNonNull(Object a, Object b) {
    if (a != null) {
      return a.toString();
    }
    return b == null ? null : b.toString();
  }

  /**
   * 列元数据 — 保留 {@code column_default} 字段仅用于诊断日志,实际比对见 {@link #checkColumnTypesOnStartup()}:
   *
   * <ul>
   *   <li>{@code data_type} 严格比
   *   <li>{@code character_maximum_length} 严格比(覆盖 varchar(N) 长度差异 — PostgreSQL data_type 不带长度)
   *   <li>{@code numeric_precision} + {@code numeric_scale} 严格比(覆盖 numeric(P,S) 精度差异)
   *   <li>{@code is_nullable} 单向比(冷比热严格才报)
   * </ul>
   */
  private record ColumnMeta(
      String dataType,
      String isNullable,
      String columnDefault,
      String characterMaximumLength,
      String numericPrecision,
      String numericScale) {}
}
