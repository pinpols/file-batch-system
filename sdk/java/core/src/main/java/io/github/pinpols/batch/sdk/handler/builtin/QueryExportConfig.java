package io.github.pinpols.batch.sdk.handler.builtin;

import io.github.pinpols.batch.sdk.handler.builtin.support.DelimitedFormat;

/**
 * {@link QueryExportHandler} 的开箱即用配置。
 *
 * @param taskType 注册的 taskType
 * @param query 导出查询(固定在配置里,**不从任务参数拼接**以避免注入;需过滤条件请用参数化视图 / 存储过程)
 * @param format 分隔符格式(分隔符 / 引号 / 是否写表头)
 * @param outputPathParam 从 {@code ctx.parameters()} 取输出文件路径的键名(默认 {@code outputPath})
 * @param fetchSize JDBC 游标流式 fetchSize(配合关闭 autoCommit 做服务端游标,默认 1000)
 */
public record QueryExportConfig(
    String taskType, String query, DelimitedFormat format, String outputPathParam, int fetchSize) {

  /** 默认:逗号 CSV 写表头、输出路径参数键 {@code outputPath}、fetchSize=1000。 */
  public static QueryExportConfig defaults(String taskType, String query) {
    return new QueryExportConfig(taskType, query, DelimitedFormat.defaults(), "outputPath", 1000);
  }
}
