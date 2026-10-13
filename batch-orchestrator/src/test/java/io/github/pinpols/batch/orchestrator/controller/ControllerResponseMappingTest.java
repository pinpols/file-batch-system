package io.github.pinpols.batch.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.pinpols.batch.orchestrator.controller.request.DataQualityRuleRequest;
import io.github.pinpols.batch.orchestrator.controller.response.BatchDayReplayEntryResponse;
import io.github.pinpols.batch.orchestrator.controller.response.BatchDayReplaySessionResponse;
import io.github.pinpols.batch.orchestrator.controller.response.DataQualityRuleResponse;
import io.github.pinpols.batch.orchestrator.controller.response.JobTaskResponse;
import io.github.pinpols.batch.orchestrator.controller.response.ResultVersionResponse;
import io.github.pinpols.batch.orchestrator.controller.response.TenantSchedulerSnapshotResponse;
import io.github.pinpols.batch.orchestrator.controller.response.WorkerRegistryResponse;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Controller Response DTO 映射保持既有 JSON 契约")
class ControllerResponseMappingTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  @DisplayName("Worker 与 claimed task 的响应 DTO 保持原 Entity JSON 字段")
  void shouldPreserveWorkerAndTaskJson_whenMappingResponseDtos() {
    WorkerRegistryEntity worker = new WorkerRegistryEntity(
        1L,
        "tenant",
        "worker",
        "group",
        JsonbString.of("[\"tag\"]"),
        "resource",
        "ONLINE",
        Instant.parse("2026-01-01T00:00:00Z"),
        2,
        8,
        null,
        null,
        "host",
        "127.0.0.1",
        "pid",
        8080,
        "build",
        "sdk",
        "pool",
        JsonbString.of("{\"task\":true}"));
    assertSameJson(worker, WorkerRegistryResponse.from(worker));

    JobTaskEntity task = new JobTaskEntity();
    task.setId(2L);
    task.setTenantId("tenant");
    task.setTaskStatus("CLAIMED");
    task.setErrorKey("task.failed");
    assertSameJson(task, JobTaskResponse.from(task));
  }

  @Test
  @DisplayName("Replay、DQ、result version 和 scheduler history DTO 保持原 JSON 字段")
  void shouldPreserveOtherControllerJson_whenMappingResponseDtos() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    BatchDayReplaySessionEntity session = BatchDayReplaySessionEntity.builder()
        .id(3L)
        .tenantId("tenant")
        .calendarCode("CAL")
        .bizDate(LocalDate.of(2026, 1, 1))
        .scope("ALL")
        .executionMode("REPLAY")
        .candidateSource("EXISTING_INSTANCES")
        .status("RUNNING")
        .createdAt(now)
        .updatedAt(now)
        .build();
    assertSameJson(session, BatchDayReplaySessionResponse.from(session));

    BatchDayReplayEntryEntity entry = BatchDayReplayEntryEntity.builder()
        .id(4L)
        .sessionId(3L)
        .tenantId("tenant")
        .jobCode("JOB")
        .status("RUNNING")
        .createdAt(now)
        .updatedAt(now)
        .build();
    assertSameJson(entry, BatchDayReplayEntryResponse.from(entry));

    DataQualityRuleEntity rule = new DataQualityRuleEntity();
    rule.setId(5L);
    rule.setTenantId("tenant");
    rule.setRuleCode("RULE");
    rule.setEnabled(true);
    rule.setCreatedAt(now);
    assertSameJson(rule, DataQualityRuleResponse.from(rule));
    DataQualityRuleRequest request = new DataQualityRuleRequest(
        rule.getId(),
        rule.getTenantId(),
        rule.getRuleCode(),
        rule.getRuleName(),
        rule.getRuleType(),
        rule.getScopeBusinessKey(),
        rule.getExpression(),
        rule.getThresholdJson(),
        rule.getSeverity(),
        rule.getEnabled(),
        rule.getDescription(),
        rule.getCreatedAt(),
        rule.getUpdatedAt());
    assertSameJson(rule, request);
    assertThat(request.toEntity()).isEqualTo(rule);

    ResultVersionEntity version = ResultVersionEntity.builder()
        .id(6L)
        .tenantId("tenant")
        .businessKey("job:JOB:2026-01-01")
        .versionNo(1)
        .status("EFFECTIVE")
        .generatedAt(now)
        .dqGateStatus("PASS")
        .build();
    assertSameJson(version, ResultVersionResponse.from(version));

    TenantSchedulerSnapshotEntity snapshot = new TenantSchedulerSnapshotEntity(
        7L,
        "tenant",
        now,
        "GROUP",
        "POLICY",
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        "DAILY",
        8,
        JsonbString.of("{\"ready\":true}"));
    assertSameJson(snapshot, TenantSchedulerSnapshotResponse.from(snapshot));
  }

  private void assertSameJson(Object entity, Object response) {
    JsonNode entityJson = objectMapper.valueToTree(entity);
    JsonNode responseJson = objectMapper.valueToTree(response);
    assertThat(responseJson).isEqualTo(entityJson);
  }
}
