package com.example.batch.sdk.handler.builtin;

import com.example.batch.sdk.handler.builtin.support.DelimitedFormat;
import java.util.List;

/**
 * {@link FileImportHandler} 的开箱即用配置。
 *
 * @param taskType 注册的 taskType
 * @param targetTable 目标表名(直接拼进 INSERT,**必须由配置方控制、勿来自不可信输入**)
 * @param columns 目标列名,与文件字段顺序一一对应(也用于 INSERT 列清单)
 * @param format 分隔符格式(分隔符 / 引号 / 是否含表头)
 * @param batchSize JDBC 批量提交条数(默认 500)
 * @param filePathParam 从 {@code ctx.parameters()} 取文件路径的键名(默认 {@code filePath})
 */
public record FileImportConfig(
    String taskType,
    String targetTable,
    List<String> columns,
    DelimitedFormat format,
    int batchSize,
    String filePathParam) {

  public FileImportConfig {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  /** 默认:逗号 CSV 含表头、batchSize=500、文件路径参数键 {@code filePath}。 */
  public static FileImportConfig defaults(
      String taskType, String targetTable, List<String> columns) {
    return new FileImportConfig(
        taskType, targetTable, columns, DelimitedFormat.defaults(), 500, "filePath");
  }
}
