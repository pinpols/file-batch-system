package io.github.pinpols.batch.worker.exports.stage;

/**
 * EXPORT 模块内跨 stage 共享的 attribute / file_record metadata 键，与 batch-worker-core
 * {@code PipelineRuntimeKeys} 平行（不污染公共契约）。
 *
 * <p>这些键由 PREPARE / GENERATE 写入，由后续 stage（REGISTER / COMPLETE）与 {@code
 * ExportStepExecutionAdapter} 按名字读回；只在单个类内部流通的键继续留在该类的私有常量。
 *
 * <p>attributes 键与落到 file_record metadata 的键同名同义，按仓库既有约定复用同一常量（同 {@code
 * DispatchRuntimeKeys} 的 detailSummary / payload 口径）。
 */
public final class ExportRuntimeKeys {

  /** PREPARE 解析出的 {@code ExportPayload}；GENERATE / REGISTER / COMPLETE 全程读它拿模板与命名规则。 */
  public static final String EXPORT_PAYLOAD = "exportPayload";

  /** GENERATE 写入的批次记录（file_batch 行，含 id / total_count）；REGISTER 读它绑 file_record。 */
  public static final String EXPORT_BATCH = "exportBatch";

  /** GENERATE 解析出的输出字符集名；REGISTER 写入 file_record metadata 供下载端复用。 */
  public static final String EXPORT_CHARSET = "exportCharset";

  /** GENERATE 判定的输出是否带 BOM；REGISTER 写入 file_record metadata。 */
  public static final String EXPORT_WITH_BOM = "exportWithBom";

  /** GENERATE 判定的输出行分隔符；REGISTER 写入 file_record metadata。 */
  public static final String EXPORT_LINE_SEPARATOR = "exportLineSeparator";

  /** GENERATE 解析出的导出数据引用（ExportDataPlugin 的注册名）；REGISTER 读它重建补偿上下文。 */
  public static final String EXPORT_DATA_REF = "exportDataRef";

  private ExportRuntimeKeys() {}
}
