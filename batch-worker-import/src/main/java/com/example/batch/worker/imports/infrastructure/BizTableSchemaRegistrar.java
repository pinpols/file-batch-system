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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import worker 启动时扫自己挂的业务库（{@code biz} schema）把 (table, columns) 清单上报到
 * {@code batch.biz_table_schema}。console-api 上传 Excel 时用于拦住 {@code targetColumn} /
 * {@code jdbcMappedImport.columnMappings[*].to} 指向不存在表/列的坏配置。
 *
 * <p>单租户部署下，worker 挂的 biz DS 代表真实的业务库；多租户 / 分库部署须扩展本类支持多 schema_name。
 */
@Slf4j
@Component
public class BizTableSchemaRegistrar {

  private final DataSource bizDataSource;
  private final BizTableSchemaMapper mapper;
  private final ObjectMapper objectMapper;

  public BizTableSchemaRegistrar(
      @Qualifier("importBusinessDataSource") DataSource bizDataSource,
      BizTableSchemaMapper mapper,
      ObjectMapper objectMapper) {
    this.bizDataSource = bizDataSource;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerOnStartup() {
    int tableCount = 0;
    try (Connection conn = bizDataSource.getConnection()) {
      DatabaseMetaData md = conn.getMetaData();
      mapper.deleteAll();
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
          mapper.upsertEntry(schema, table, objectMapper.writeValueAsString(cols));
          tableCount++;
        }
      }
      log.info("biz_table_schema snapshot refreshed: schema=biz, tables={}", tableCount);
    } catch (Exception ex) {
      // 失败降级：console-api 查到空表就跳过 targetColumn 校验（兼容首次部署无 biz 库的场景）
      log.error("biz_table_schema snapshot failed: {}", ex.getMessage(), ex);
    }
  }
}
