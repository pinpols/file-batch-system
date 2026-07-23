package io.github.pinpols.batch.trigger.service;

import java.time.LocalDate;
import lombok.Getter;

/**
 * ADR-043:scheduled fire 时声明了 {@code dependsOnJobCode} 的上游在同 bizDate 尚未就绪。
 *
 * <p>由 {@code launchScheduled} 在依赖未就绪时抛出(取代旧的“返回 skipped 直接丢批”)。Quartz 执行入口据此创建 one-shot retry
 * trigger，在 readinessWindow 内固定原始 fire 时间持续重检。
 *
 * <p>这是预期的控制流分支(非系统故障)，携带租户/job/依赖/bizDate 供 Quartz retry 记录日志。
 */
@Getter
public class UpstreamNotReadyException extends RuntimeException {

  private final String tenantId;
  private final String jobCode;
  private final String dependsOnJobCode;
  private final transient LocalDate bizDate;

  public UpstreamNotReadyException(
      String tenantId, String jobCode, String dependsOnJobCode, LocalDate bizDate) {
    super(
        "upstream not ready: tenantId="
            + tenantId
            + " jobCode="
            + jobCode
            + " dependsOn="
            + dependsOnJobCode
            + " bizDate="
            + bizDate);
    this.tenantId = tenantId;
    this.jobCode = jobCode;
    this.dependsOnJobCode = dependsOnJobCode;
    this.bizDate = bizDate;
  }
}
