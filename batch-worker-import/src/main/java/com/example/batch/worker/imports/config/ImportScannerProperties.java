package com.example.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.scanner")
public class ImportScannerProperties {

  private boolean enabled = true;
  private long pollIntervalMillis = 30000L;
  private int batchSize = 200;
  private String prefix = "ingress/";
  private boolean requireDoneFile = false;

  /** 完成标记后缀(含点),默认 .done;上游用 .chk / .ok 时改此值。 */
  private String doneFileSuffix = ".done";

  /** 标记命名:APPEND_FULL_NAME(默认,全名+后缀)或 REPLACE_EXTENSION(去末段扩展名,旧行为)。 */
  private String doneFileNaming = "APPEND_FULL_NAME";

  /** 标记格式:MARKER(默认,空标记)或 MANIFEST(.chk 为 JSON,校验 size+注入 checksum/recordCount)。 */
  private String doneFileFormat = "MARKER";

  /** 批次清单(ADR-040)开关:true 时 scanner 识别批次清单对象,按其 requiredFiles 动态注入 required_file_set。 */
  private boolean batchManifestEnabled = false;

  /** 批次清单对象后缀(含点),默认 .batch.json。 */
  private String batchManifestSuffix = ".batch.json";

  private long stabilityWindowSeconds = 30L;
  private String sourceType = "SYSTEM";
  private String defaultBizType = "IMPORT_SCAN";

  /**
   * 文件名/对象名解析 bizDate 的正则；必须包含命名捕获组 {@code (?<bizDate>...)}，匹配到的 token 用 {@code yyyyMMdd} 解析。
   *
   * <p>例如 {@code "(?<bizDate>\\d{8})"} 可从 {@code import-20260505-orders.csv} 抽出 {@code 2026-05-05}。
   * 留空表示不启用，scanner 退化到 {@link #defaultBizDate}。
   */
  private String bizDatePattern = "";

  /**
   * {@link #bizDatePattern} 不命中时的兜底业务日（{@code yyyy-MM-dd}）。空 / 非法 → scanner
   * 跳过该对象（不再静默使用机器当前自然日，避免日切前后误归档）。
   */
  private String defaultBizDate = "";

  private final Arrival arrival = new Arrival();

  @Data
  public static class Arrival {
    private boolean enabled = false;
    private String fileGroupCode = "";
    private String waitFileGroupMode = "ALL_OF";
    private String requiredFileSet = "";
    private String arrivalTimeoutAction = "MANUAL_CONFIRM";
    private long expectedArrivalDelaySeconds = 0L;
    private long latestTolerableDelaySeconds = 600L;
    private boolean triggerOnComplete = true;
    private boolean allowEmptyRun = false;
    private boolean allowSkipBizDate = false;
    private boolean notifyManual = true;
    private String notifyChannels = "";
  }
}
