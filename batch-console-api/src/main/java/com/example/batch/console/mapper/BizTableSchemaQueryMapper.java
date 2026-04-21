package com.example.batch.console.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 只读查询 {@code batch.biz_table_schema}：上传 Excel 校验 target 列名 / jdbc_mapped_import 列映射时
 * 对照 worker 上报的真实业务库 schema，拦住指向不存在表/列的坏配置。
 */
public interface BizTableSchemaQueryMapper {

  /** 返回单表的 columns 行（columns 字段是 JSON 字符串）。表不存在时返 null。 */
  Map<String, Object> selectByTable(
      @Param("schemaName") String schemaName, @Param("tableName") String tableName);

  /** 全表列表（schema.table → columns），空表示 worker 未上报，校验应降级。 */
  List<Map<String, Object>> selectAll();
}
