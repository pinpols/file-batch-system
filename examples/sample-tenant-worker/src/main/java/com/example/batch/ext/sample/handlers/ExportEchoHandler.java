package com.example.batch.ext.sample.handlers;

import com.example.batch.sdk.handler.SdkAbstractExportHandler;
import com.example.batch.sdk.handler.SdkRowResult;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-036 Export 模板 sample —— tenant → external demo。模拟"查 2 行 → 写出文件"。
 *
 * <p>真业务:openSink 创建 S3/SFTP writer,buildQuery 拼 SQL,streamRows 流式读租户表,formatRow 写一行,writeOut 收尾上传。
 */
public class ExportEchoHandler extends SdkAbstractExportHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(ExportEchoHandler.class);

  @Override
  public String taskType() {
    return "sample_export_echo";
  }

  @Override
  protected void openSink(SdkTaskContext ctx) {
    log.info("export sample: openSink for tenant={}", ctx.tenantId());
  }

  @Override
  protected String buildQuery(SdkTaskContext ctx) {
    return "SELECT id FROM my_table WHERE biz_date = :bizDate";
  }

  @Override
  protected Stream<String> streamRows(SdkTaskContext ctx, String query) {
    log.info("export sample: streamRows query={}", query);
    // 真业务:jdbcTemplate.queryForStream(query, rowMapper),模板会 try-with-resources 关掉 ResultSet
    return Stream.of("out-1", "out-2");
  }

  @Override
  protected void formatRow(SdkTaskContext ctx, String row) {
    log.info("export sample: formatRow {}", row);
  }

  @Override
  protected SdkTaskResult writeOut(SdkTaskContext ctx, SdkRowResult counts) {
    // 真业务:flush + close + 上传,把文件 URI 放 output
    return SdkTaskResult.ok(
        "exported " + counts.success() + " rows",
        Map.of("fileUri", "s3://tenant-bucket/export-" + ctx.taskId() + ".csv"));
  }
}
