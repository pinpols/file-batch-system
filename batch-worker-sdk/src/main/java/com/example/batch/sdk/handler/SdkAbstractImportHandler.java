package com.example.batch.sdk.handler;

import com.example.batch.sdk.handler.typed.SdkAbstractTypedImportHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ADR-036 — Import 模板:external → tenant(file → tenant DB)。
 *
 * <p>本类是 {@link SdkAbstractTypedImportHandler} 在「裸 Map 入参」下的特例(无需强类型入参的简单场景):钩子只收 {@code ctx}, 分批 /
 * 流式 / 计数兜底的模板循环全部复用 typed 基类,避免重复实现。需要强类型入参时直接用 {@link SdkAbstractTypedImportHandler}。
 *
 * @param <R> 行类型
 */
public abstract class SdkAbstractImportHandler<R>
    extends SdkAbstractTypedImportHandler<Map<String, Object>, Void, R> {

  /** 打开数据源(连 SFTP / 下载文件 / 开 stream)。 */
  protected abstract void openSource(SdkTaskContext ctx) throws Exception;

  /**
   * 返回行流(逐行解析)。模板用 try-with-resources 关闭,保证背后的 {@code ResultSet} / {@code InputStream}
   * 在读完或异常时都释放;租户可直接返 {@code jdbcTemplate.queryForStream(...)} / {@code Files.lines(...)}。
   */
  protected abstract Stream<R> readRows(SdkTaskContext ctx) throws Exception;

  /** 批量写入租户自家目标表。 */
  protected abstract void loadBatch(SdkTaskContext ctx, List<R> batch) throws Exception;

  @Override
  protected final void openSource(Map<String, Object> input, SdkTaskContext ctx) throws Exception {
    openSource(ctx);
  }

  @Override
  protected final Stream<R> readRows(Map<String, Object> input, SdkTaskContext ctx)
      throws Exception {
    return readRows(ctx);
  }

  @Override
  protected final void loadBatch(Map<String, Object> input, SdkTaskContext ctx, List<R> batch)
      throws Exception {
    loadBatch(ctx, batch);
  }
}
