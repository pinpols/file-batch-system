package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.core.mapper.BizTableSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Import worker 启动时扫自己挂的业务库（{@code biz} schema）把 (table, columns) 清单上报到 {@code
 * batch.biz_table_schema}。console-api 上传 Excel 时用于拦住 {@code targetColumn} / {@code
 * jdbcMappedImport.columnMappings[*].to} 指向不存在表/列的坏配置。
 *
 * <p>单租户部署下，worker 挂的 biz DS 代表真实的业务库；多租户 / 分库部署须扩展本类支持多 schema_name。
 */
@Slf4j
@Component
public class BizTableSchemaRegistrar {

  private final DataSource bizDataSource;
  private final BizTableSchemaMapper mapper;
  private final ObjectMapper objectMapper;
  private final PlatformTransactionManager transactionManager;

  public BizTableSchemaRegistrar(
      @Qualifier("importBusinessDataSource") DataSource bizDataSource,
      BizTableSchemaMapper mapper,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.bizDataSource = bizDataSource;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.transactionManager = transactionManager;
  }

  /** biz 元数据快照单元:一张表 + 其列定义 JSON。 */
  private record TableColumns(String schema, String table, String columnsJson) {}

  // P1-6: @EventListener 上原挂 @Transactional(REQUIRES_NEW) 违反 CLAUDE.md #4(@Transactional 只放
  // Service 公共方法),且 EventListener 无外层事务时 REQUIRES_NEW≈REQUIRED 纯属误用。改为先在事务外只读
  // 收集 biz 元数据快照,再用 TransactionTemplate 显式包裹 deleteAll + N 条 upsertEntry 原子覆盖写
  // (对齐 AbstractStepBeanRegistrar 的合规范式)。
  @EventListener(ApplicationReadyEvent.class)
  public void registerOnStartup() {
    List<TableColumns> snapshot = new ArrayList<>();
    try (Connection conn = bizDataSource.getConnection()) {
      DatabaseMetaData md = conn.getMetaData();
      try (ResultSet tables = md.getTables(null, "biz", "%", new String[] {"TABLE"})) {
        while (tables.next()) {
          String schema = tables.getString("TABLE_SCHEM");
          String table = tables.getString("TABLE_NAME");
          List<Map<String, Object>> cols = new ArrayList<>();
          try (ResultSet colsRs = md.getColumns(null, schema, table, "%")) {
            while (colsRs.next()) {
              Map<String, Object> col = new LinkedHashMap<>();
              col.put("name", colsRs.getString("COLUMN_NAME"));
              col.put("type", colsRs.getString("TYPE_NAME"));
              col.put("nullable", "YES".equalsIgnoreCase(colsRs.getString("IS_NULLABLE")));
              cols.add(col);
            }
          }
          snapshot.add(new TableColumns(schema, table, objectMapper.writeValueAsString(cols)));
        }
      }
    } catch (Exception ex) {
      // 失败降级：console-api 查到空表就跳过 targetColumn 校验（兼容首次部署无 biz 库的场景）
      log.error("biz_table_schema snapshot failed (read biz metadata): {}", ex.getMessage(), ex);
      return;
    }
    try {
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                mapper.deleteAll();
                for (TableColumns tc : snapshot) {
                  mapper.upsertEntry(tc.schema(), tc.table(), tc.columnsJson());
                }
              });
      log.info("biz_table_schema snapshot refreshed: schema=biz, tables={}", snapshot.size());
    } catch (Exception ex) {
      log.error("biz_table_schema snapshot failed (write registry): {}", ex.getMessage(), ex);
    }
  }
}
