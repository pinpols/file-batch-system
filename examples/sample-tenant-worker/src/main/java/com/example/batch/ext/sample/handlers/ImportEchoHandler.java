package com.example.batch.ext.sample.handlers;

import com.example.batch.sdk.handler.SdkAbstractImportHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-036 Import 模板 sample —— external → tenant demo。模拟"读 3 行 → 批量写入"。
 *
 * <p>真业务:openSource 连 SFTP/S3 拉文件,readRows 解析行,loadBatch 写租户自家 staging table。
 */
public class ImportEchoHandler extends SdkAbstractImportHandler<String> {

  private static final Logger log = LoggerFactory.getLogger(ImportEchoHandler.class);

  @Override
  public String taskType() {
    return "sample_import_echo";
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
  protected Iterator<String> readRows(SdkTaskContext ctx) {
    // 真业务:逐行从文件 stream 读;这里模拟 3 行
    return List.of("row-1", "row-2", "row-3").iterator();
  }

  @Override
  protected void loadBatch(SdkTaskContext ctx, List<String> batch) {
    // 真业务:批量 INSERT 到租户自家表;这里只 log
    log.info("import sample: loadBatch size={} rows={}", batch.size(), batch);
  }
}
