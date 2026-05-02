package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultPartitionDispatchServiceTest {

  @Test
  void enrichPayload_addsDerivedFieldsWhenMissing() {
    LaunchRequest request =
        new LaunchRequest(
            "tc",
            "TC_EXPORT_RISK_ALERT",
            LocalDate.of(2026, 4, 22),
            TriggerType.SCHEDULED,
            "req-1",
            "trace-1",
            Map.of());
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setBatchNo("2026-04-22");

    Map<String, Object> payload =
        DefaultPartitionDispatchService.enrichPayload(request, jobInstance, Map.of());

    assertThat(payload)
        .containsEntry("batchNo", "2026-04-22")
        .containsEntry("bizDate", "2026-04-22")
        .containsEntry("jobCode", "TC_EXPORT_RISK_ALERT");
  }

  @Test
  void enrichPayload_keepsExplicitFieldsUntouched() {
    LaunchRequest request =
        new LaunchRequest(
            "tc",
            "TC_EXPORT_RISK_ALERT",
            LocalDate.of(2026, 4, 22),
            TriggerType.SCHEDULED,
            "req-2",
            "trace-2",
            Map.of("batchNo", "REQ-BATCH"));
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setBatchNo("INSTANCE-BATCH");

    Map<String, Object> payload =
        DefaultPartitionDispatchService.enrichPayload(
            request,
            jobInstance,
            Map.of("batchNo", "REQ-BATCH", "bizDate", "2026-04-01", "jobCode", "CUSTOM_JOB"));

    assertThat(payload)
        .containsEntry("batchNo", "REQ-BATCH")
        .containsEntry("bizDate", "2026-04-01")
        .containsEntry("jobCode", "CUSTOM_JOB");
  }
}
