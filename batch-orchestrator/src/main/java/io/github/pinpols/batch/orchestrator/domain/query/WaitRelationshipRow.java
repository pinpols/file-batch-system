package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/** 不含冻结载荷的只读等待事实。ID 字符串避免浏览器精度损失。 */
@Data
public class WaitRelationshipRow {
  private String id;
  private String waitType;
  private String tenantId;
  private String subjectCode;
  private LocalDate bizDate;
  private String waitStatus;
  private String dependencySnapshot;
  private boolean dependencySnapshotValid;
  private String reason;
  private Instant updatedAt;
  private String requestId;
}
