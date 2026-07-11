package io.github.pinpols.batch.console.web.response.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigMapResponseTest {

  @Test
  void quotaPolicyShouldNormalizeSnakeCaseColumns() {
    Instant updatedAt = Instant.parse("2026-07-11T04:00:00Z");

    QuotaPolicyResponse response =
        QuotaPolicyResponse.from(
            Map.of(
                "id",
                7L,
                "tenant_id",
                "ta",
                "policy_code",
                "default",
                "max_running_jobs_per_tenant",
                10,
                "enabled",
                true,
                "updated_at",
                Timestamp.from(updatedAt)));

    assertThat(response.tenantId()).isEqualTo("ta");
    assertThat(response.policyCode()).isEqualTo("default");
    assertThat(response.maxRunningJobsPerTenant()).isEqualTo(10);
    assertThat(response.updatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void resourceQueueShouldNormalizeSnakeCaseColumns() {
    ResourceQueueResponse response =
        ResourceQueueResponse.from(
            Map.of(
                "id",
                9L,
                "tenant_id",
                "ta",
                "queue_code",
                "import",
                "queue_type",
                "IMPORT",
                "max_running_partitions",
                8,
                "enabled",
                true));

    assertThat(response.queueCode()).isEqualTo("import");
    assertThat(response.queueType()).isEqualTo("IMPORT");
    assertThat(response.maxRunningPartitions()).isEqualTo(8);
  }

  @Test
  void syncLogShouldExposeTypedTimestamp() {
    Instant createdAt = Instant.parse("2026-07-11T04:00:00Z");

    ConfigSyncLogResponse response =
        ConfigSyncLogResponse.from(
            Map.of(
                "id",
                11L,
                "tenantId",
                "ta",
                "syncDirection",
                "IMPORT",
                "totalItems",
                5,
                "successItems",
                5,
                "failedItems",
                0,
                "skippedItems",
                0,
                "syncStatus",
                "SUCCESS",
                "createdAt",
                Timestamp.from(createdAt)));

    assertThat(response.createdAt()).isEqualTo(createdAt);
  }
}
