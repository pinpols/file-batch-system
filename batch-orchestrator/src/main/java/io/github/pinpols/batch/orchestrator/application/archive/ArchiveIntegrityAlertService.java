package io.github.pinpols.batch.orchestrator.application.archive;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.view.ArchiveIntegrityCandidateSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 将归档完整性异常写入既有持久告警台账，避免只依赖进程日志发现被隔离的运行数据。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveIntegrityAlertService {

  private static final String ALERT_TYPE = "SUCCESS_INSTANCE_ARCHIVE_INTEGRITY";
  private static final String RESOURCE_KEY = "success-instance-archive-integrity";

  private final AlertEventService alertEventService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void emitSuccessInstanceArchiveIssues(
      Instant cutoff, List<ArchiveIntegrityCandidateSummary> summaries) {
    for (ArchiveIntegrityCandidateSummary summary : summaries) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("archiveDomain", "SUCCESS_INSTANCE");
      detail.put("affectedInstanceCount", summary.affectedInstanceCount());
      detail.put("sampleInstanceId", summary.sampleInstanceId());
      detail.put("cutoff", cutoff);
      detail.put("action", "manual-integrity-review-required");

      alertEventService.emit(AlertEmitRequest.builder()
          .tenantId(summary.tenantId())
          .serviceName("batch-orchestrator")
          .alertType(ALERT_TYPE)
          .severity("ERROR")
          .title("成功实例归档发现运行数据引用归属异常")
          .detailJson(JsonUtils.toJson(detail))
          .resourceKey(RESOURCE_KEY)
          .build());
      log.warn(
          "success-instance archive integrity issue persisted: tenantId={}, affectedInstances={}, cutoff={}",
          summary.tenantId(),
          summary.affectedInstanceCount(),
          cutoff);
    }
  }
}
