package io.github.pinpols.batch.worker.imports.stage;

/**
 * IMPORT 模块内跨类共享的 attribute / file_record metadata 键，与 batch-worker-core
 * {@code PipelineRuntimeKeys} 平行（不污染公共契约）。
 *
 * <p>收键判据是「是否被本模块多个类按字符串读写」：{@code PreprocessStep} 写入、{@code
 * ImportRecordGovernanceService} 与后续 step 读回的键登记在此；只在单个类内部流通的键继续留在该类的私有常量，
 * 避免把模块键表变成垃圾桶。
 *
 * <p>attributes 键与落到 file_record metadata 的键同名同义，按仓库既有约定复用同一常量（同 {@code
 * DispatchRuntimeKeys} 的 detailSummary / payload 口径）。
 */
public final class ImportRuntimeKeys {

  /** LOAD 累计入库成功行数；同时写入 file_record metadata 供问题定位。 */
  public static final String SUCCESS_COUNT = "successCount";

  /** 非 skip 策略下的失败行数（BAD_RECORD_GOVERNANCE 计数）；同时写入 file_record metadata。 */
  public static final String FAILED_COUNT = "failedCount";

  /** VALIDATE / LOAD 判定需要人工复核：为 {@code true} 时 LOAD 不自动推进文件状态。 */
  public static final String MANUAL_REVIEW_REQUIRED = "manualReviewRequired";

  /** PREPROCESS 字符集探测结论：疑似编码与实际不符（如声明 GBK 实为 UTF-8）。 */
  public static final String CHARSET_SUSPECT = "charsetSuspect";

  /** PREPROCESS 转码时的非法字节替换次数（{@code invalid_char_policy=REPLACE}）。 */
  public static final String REPLACEMENT_COUNT = "replacementCount";

  /** PREPROCESS 探测到的实际字符集名；同时写入 file_record metadata。 */
  public static final String DETECTED_CHARSET = "detectedCharset";

  /** BAD_RECORD_GOVERNANCE 记录的最后一条坏记录（原样 JSON / 字符串），供失败续跑定位。 */
  public static final String LAST_BAD_RECORD = "lastBadRecord";

  /** BAD_RECORD_GOVERNANCE 处理到的最后一条记录号，续跑从此处继续。 */
  public static final String LAST_PROCESSED_RECORD_NO = "lastProcessedRecordNo";

  /** BAD_RECORD_GOVERNANCE 记录的最后一个错误码，失败上报读取。 */
  public static final String LAST_ERROR_CODE = "lastErrorCode";

  /** BAD_RECORD_GOVERNANCE 记录的最后一个错误信息（已按模板脱敏），失败上报读取。 */
  public static final String LAST_ERROR_MESSAGE = "lastErrorMessage";

  private ImportRuntimeKeys() {}
}
