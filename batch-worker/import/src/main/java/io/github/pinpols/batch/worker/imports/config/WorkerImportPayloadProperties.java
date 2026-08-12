package io.github.pinpols.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Import worker payload 大小/堆比例守护。
 *
 * <p>{@code ReceiveStep} 之前的 2 个 {@code @Value} 收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.import")
@SuppressWarnings("ConfigurationProperties") // 与 ImportWorkerConfiguration 共享前缀，子键互不重叠。
public class WorkerImportPayloadProperties {

  /** 单条 import payload 最大 MB(硬上限)。默认 100。 */
  private int maxPayloadSizeMb = 100;

  /**
   * payload 相对堆大小的安全比例(默认 0.2 = 20%)。PREPROCESS 阶段会产生 byte[] + String (UTF-16) + decode 副本等多份中间态,留
   * 80% 给 JVM / GC / 其它业务。
   */
  private double payloadHeapRatio = 0.2;

  /** Excel 单文件字节上限。 */
  private long maxExcelBytes = 200L * 1024 * 1024;

  /** 预处理结果超过该阈值时落临时文件，避免重复堆内副本。 */
  private int preprocessSpoolBytes = 16 * 1024 * 1024;

  /** 从对象存储整块读取时的单文件字节上限。 */
  private long maxObjectBytes = 512L * 1024 * 1024;

  /** 解压后的绝对字节上限。 */
  private long maxDecompressBytes = 256L * 1024 * 1024;

  /** 解压相对输入大小的最大膨胀倍数。 */
  private int maxDecompressRatio = 50;
}
