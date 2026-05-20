package com.example.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Import worker payload 大小/堆比例守护。
 *
 * <p>{@code ReceiveStep} 之前的 2 个 {@code @Value} 收敛到这里。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.import")
public class WorkerImportPayloadProperties {

  /** 单条 import payload 最大 MB(硬上限)。默认 100。 */
  private int maxPayloadSizeMb = 100;

  /**
   * payload 相对堆大小的安全比例(默认 0.2 = 20%)。PREPROCESS 阶段会产生 byte[] + String (UTF-16) + decode 副本等多份中间态,留
   * 80% 给 JVM / GC / 其它业务。
   */
  private double payloadHeapRatio = 0.2;
}
