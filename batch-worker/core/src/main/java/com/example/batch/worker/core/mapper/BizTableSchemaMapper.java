package com.example.batch.worker.core.mapper;

import org.apache.ibatis.annotations.Param;

/** {@code batch.biz_table_schema} 快照刷新入口：worker 启动扫业务库后覆盖式写入。 查询在 console-api 侧独立走只读 mapper。 */
public interface BizTableSchemaMapper {

  /** 删除全表，让单个 worker（单租户业务库）刷新时整体重建；多租户部署另行改造 {@code schema_name} 维度。 */
  int deleteAll();

  int upsertEntry(
      @Param("schemaName") String schemaName,
      @Param("tableName") String tableName,
      @Param("columns") String columnsJson);
}
