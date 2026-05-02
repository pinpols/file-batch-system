package com.example.batch.common.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * PostgreSQL {@code information_schema} 系统目录查询。
 *
 * <p>仅供启动期校验 / 漂移检测使用（{@code BatchStartupSelfCheck} / {@code ArchiveSchemaDriftCheck}），不参与业务读写。
 *
 * <p>跨所有 batch 模块共用。每个模块 {@code @MapperScan} 必须包含 {@code com.example.batch.common.mapper}。
 */
public interface InformationSchemaMapper {

  /** 给定 schemaName 是否存在。 */
  int countSchema(@Param("schemaName") String schemaName);

  /** 给定 (schema, table) 是否存在。 */
  int countTable(@Param("schemaName") String schemaName, @Param("tableName") String tableName);

  /** 给定 (schema, table, column) 是否存在。 */
  int countColumn(
      @Param("schemaName") String schemaName,
      @Param("tableName") String tableName,
      @Param("columnName") String columnName);

  /** 给定 (schema, table) 的所有列名（无序）。表不存在时返空列表。 */
  List<String> listColumns(
      @Param("schemaName") String schemaName, @Param("tableName") String tableName);
}
