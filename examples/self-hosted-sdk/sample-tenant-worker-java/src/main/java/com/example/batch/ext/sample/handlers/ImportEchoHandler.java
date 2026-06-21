package com.example.batch.ext.sample.handlers;

import com.example.batch.sdk.handler.SdkAbstractImportHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskTypeDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-036 Import 模板 sample —— external → tenant demo。模拟"读 3 行 → 批量写入"。
 *
 * <p>真业务:openSource 连 SFTP/S3 拉文件,readRows 解析行,loadBatch 写租户自家 staging table。
 *
 * <p>SDK Phase 3 M3.1 端到端示范:本 handler 重写 {@link #descriptor()} 声明 taskType 元数据(defaults /
 * inputSchema / 模板变量 / 版本)。register 时随 {@code taskTypes[]} 上报平台 → upsert 到
 * {@code custom_task_type_registry} → console 据 inputSchema 渲染表单、据 defaults 预填 → 派单时合并
 * {@code defaults + node.parameters + ${模板}}。
 */
public class ImportEchoHandler extends SdkAbstractImportHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(ImportEchoHandler.class);

  @Override
  public String taskType() {
    return "sample_import_echo";
  }

  /**
   * 声明 taskType 描述符 —— 无需填 {@code code},框架装配时以 {@link #taskType()} 为权威。
   *
   * <p>纪律:敏感凭据(SFTP 密码 / S3 secret)<b>禁止</b>走 {@code defaults} —— 这些值会写入数据库
   * {@code custom_task_type_registry} 并回显 console;凭据走 worker 进程的环境变量。
   */
  @Override
  public SdkTaskTypeDescriptor descriptor() {
    return SdkTaskTypeDescriptor.builder()
        .displayName("示范导入(echo)")
        .version("v1")
        .defaults(Map.of("batchSize", 2, "targetTable", "staging_${bizDate}"))
        .inputSchema(
            Map.of(
                "type",
                "object",
                "required",
                List.of("sourcePath"),
                "properties",
                Map.of(
                    "sourcePath", Map.of("type", "string", "title", "源文件路径"),
                    "batchSize", Map.of("type", "integer", "minimum", 1, "default", 2))))
        .templateVariables(List.of("bizDate", "dataIntervalStart", "dataIntervalEnd"))
        .build();
  }

  @Override
  protected int batchSize() {
    return 2;
  }

  @Override
  protected void openSource(SdkTaskContext ctx) {
    log.info("import sample: openSource for tenant={}", ctx.tenantId());
  }

  @Override
  protected Stream<String> readRows(SdkTaskContext ctx) {
    // 真业务:逐行从文件 stream 读(如 Files.lines / jdbcTemplate.queryForStream),模板会 try-with-resources 关掉
    return Stream.of("row-1", "row-2", "row-3");
  }

  @Override
  protected void loadBatch(SdkTaskContext ctx, List<String> batch) {
    // 真业务:批量 INSERT 到租户自家表;这里只 log
    log.info("import sample: loadBatch size={} rows={}", batch.size(), batch);
  }
}
