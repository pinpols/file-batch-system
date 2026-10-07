package io.github.pinpols.batch.console.application.contract.response.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置映射响应: 下划线列名归一化与时间字段类型转换")
class ConfigMapResponseTest {

  @Test
  @DisplayName("配额策略行使用下划线列名时,租户、策略编码、并发上限与更新时间应正确映射")
  void shouldNormalizeQuotaPolicy_whenColumnsAreSnakeCase() {
    Instant updatedAt = Instant.parse("2026-07-11T04:00:00Z");

    QuotaPolicyResponse response = QuotaPolicyResponse.from(Map.of(
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
  @DisplayName("资源队列行使用下划线列名时,队列编码、队列类型与分区并发上限应正确映射")
  void shouldNormalizeResourceQueue_whenColumnsAreSnakeCase() {
    ResourceQueueResponse response = ResourceQueueResponse.from(Map.of(
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
  @DisplayName("配置同步日志的创建时间应由数据库时间戳转换为同一瞬间")
  void shouldExposeTypedTimestamp_whenSyncLogCreatedAtPresent() {
    Instant createdAt = Instant.parse("2026-07-11T04:00:00Z");

    ConfigSyncLogResponse response = ConfigSyncLogResponse.from(Map.of(
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
