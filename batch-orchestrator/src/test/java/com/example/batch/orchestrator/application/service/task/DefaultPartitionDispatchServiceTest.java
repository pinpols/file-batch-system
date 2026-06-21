package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import java.time.LocalDate;
import java.util.HashMap;
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

  @Test
  void enrichBundleBinding_injectsBindingForBundlePartition() {
    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setSourceFileId(42L);
    partition.setTemplateCode("RISK_IMPORT_V2");
    partition.setTargetRef("biz.risk_alert");
    Map<String, Object> payload = new HashMap<>();

    DefaultPartitionDispatchService.enrichBundleBinding(payload, partition);

    assertThat(payload)
        .containsEntry("sourceFileId", 42L)
        .containsEntry("templateCode", "RISK_IMPORT_V2")
        .containsEntry("targetRef", "biz.risk_alert");
  }

  @Test
  void enrichBundleBinding_leavesNormalPartitionPayloadUnchanged() {
    // 普通(非束)partition 三根绑定列均为空，payload 不得新增任何字段——保证存量导入零影响。
    JobPartitionEntity partition = new JobPartitionEntity();
    Map<String, Object> payload = new HashMap<>();

    DefaultPartitionDispatchService.enrichBundleBinding(payload, partition);

    assertThat(payload).doesNotContainKeys("sourceFileId", "templateCode", "targetRef");
  }

  @Test
  void enrichBundleBinding_nullPartitionIsNoOp() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("batchNo", "2026-04-22");

    DefaultPartitionDispatchService.enrichBundleBinding(payload, null);

    assertThat(payload).hasSize(1).containsEntry("batchNo", "2026-04-22");
  }
}
