package io.github.pinpols.batch.console.shared.command;

import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

/** 编排器补偿命令载荷。仅承载跨应用边界的数据。 */
@Getter
@Builder(toBuilder = true)
public class CompensationPayload {
  private final String tenantId;
  private final String compensationType;
  private final Long targetId;
  private final String targetInstanceNo;
  private final String jobCode;
  private final LocalDate bizDate;
  private final String batchNo;
  private final Long relatedFileId;
  private final String channelCode;
  private final String reason;
  private final String operatorId;
  private final String approvalId;
  private final String strategy;
  private final String traceId;
  private final String resultPolicy;
  private final String configVersionPolicy;
  private final Integer configVersion;
}
