package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：主要的 Worker 路由类型在启动后各自发出匹配的 task / outbox {@code event_type} （IMPORT / EXPORT / DISPATCH）。
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobTypeOutboxChainIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private LaunchService launchService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void importJobShouldWriteImportOutboxEvent() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);

    LaunchRequest importRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.API)
            .requestId(seed.requestId())
            .traceId("tr-import")
            .params(Map.of())
            .build();
    launchService.launch(importRequest);

    assertThat(LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "IMPORT"))
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  void exportJobShouldWriteExportOutboxEvent() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "EXPORT", "EXPORT", TriggerType.MANUAL);

    LaunchRequest exportRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.MANUAL)
            .requestId(seed.requestId())
            .traceId("tr-export")
            .params(Map.of())
            .build();
    launchService.launch(exportRequest);

    assertThat(LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "EXPORT"))
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  void dispatchJobShouldWriteDispatchOutboxEvent() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "DISPATCH", "DISPATCH", TriggerType.EVENT);

    LaunchRequest dispatchRequest =
        LaunchRequest.builder()
            .tenantId(TENANT)
            .jobCode(seed.jobCode())
            .bizDate(LocalDate.of(2026, 1, 15))
            .triggerType(TriggerType.EVENT)
            .requestId(seed.requestId())
            .traceId("tr-dispatch")
            .params(Map.of())
            .build();
    launchService.launch(dispatchRequest);

    assertThat(LaunchIntegrationFixture.countOutboxByEventType(jdbcTemplate, TENANT, "DISPATCH"))
        .isGreaterThanOrEqualTo(1L);
  }
}
