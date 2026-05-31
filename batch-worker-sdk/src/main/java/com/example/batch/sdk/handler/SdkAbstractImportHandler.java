package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** ADR-036 — Import 模板:external → tenant(file → tenant DB)。 */
public abstract class SdkAbstractImportHandler<R> extends SdkAbstractTaskHandler {
  /** 打开数据源(连 SFTP / 下载文件 / 开 stream)。 */
  protected abstract void openSource(SdkTaskContext ctx) throws Exception;

  /** 返回行迭代器(逐行解析)。 */
  protected abstract Iterator<R> readRows(SdkTaskContext ctx) throws Exception;

  /** 批量写入租户自家目标表。 */
  protected abstract void loadBatch(SdkTaskContext ctx, List<R> batch) throws Exception;

  /** 批大小,默认 1000,可覆盖。 */
  protected int batchSize() {
    return 1000;
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      openSource(ctx);
      SdkRowResult counts = new SdkRowResult();
      List<R> buf = new ArrayList<>(batchSize());
      Iterator<R> it = readRows(ctx);
      while (it.hasNext()) {
        buf.add(it.next());
        if (buf.size() >= batchSize()) {
          flush(ctx, buf, counts);
        }
      }
      if (!buf.isEmpty()) {
        flush(ctx, buf, counts);
      }
      return SdkTaskResult.ok("imported " + counts.success() + " rows", counts.toOutput());
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private void flush(SdkTaskContext ctx, List<R> buf, SdkRowResult counts) throws Exception {
    loadBatch(ctx, buf);
    counts.addSuccess(buf.size());
    buf.clear();
  }
}
