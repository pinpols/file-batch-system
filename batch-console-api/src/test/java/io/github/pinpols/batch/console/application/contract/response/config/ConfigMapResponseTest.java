package io.github.pinpols.batch.console.application.contract.response.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.domain.config.view.ConfigSyncLogRow;
import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import io.github.pinpols.batch.console.domain.rbac.view.TenantQuotaPolicyRow;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置映射响应: 下划线列名归一化与时间字段类型转换")
class ConfigMapResponseTest {

  @Test
  @DisplayName("配额策略行具名投影时,租户、策略编码、并发上限与更新时间应正确映射")
  void shouldMapQuotaPolicy_whenRowIsTyped() {
    Instant updatedAt = Instant.parse("2026-07-11T04:00:00Z");

    QuotaPolicyResponse response = QuotaPolicyResponse.from(new TenantQuotaPolicyRow(
        7L, "ta", "default", 10, 0, 0, 1, true, null, null, updatedAt, null, null, null, null, null,
        null));

    assertThat(response.tenantId()).isEqualTo("ta");
    assertThat(response.policyCode()).isEqualTo("default");
    assertThat(response.maxRunningJobsPerTenant()).isEqualTo(10);
    assertThat(response.updatedAt()).isEqualTo(updatedAt);
  }

  @Test
  @DisplayName("资源队列行具名投影时,队列编码、队列类型与分区并发上限应正确映射")
  void shouldMapResourceQueue_whenRowIsTyped() {
    ResourceQueueResponse response = ResourceQueueResponse.from(new ResourceQueueRow(
        9L, "ta", "import", null, "IMPORT", null, 8, null, null, null, null, null, true, null, null,
        null, null, null, null, null));

    assertThat(response.queueCode()).isEqualTo("import");
    assertThat(response.queueType()).isEqualTo("IMPORT");
    assertThat(response.maxRunningPartitions()).isEqualTo(8);
  }

  @Test
  @DisplayName("配置同步日志的创建时间应由数据库时间戳转换为同一瞬间")
  void shouldExposeTypedTimestamp_whenSyncLogCreatedAtPresent() {
    Instant createdAt = Instant.parse("2026-07-11T04:00:00Z");

    ConfigSyncLogResponse response = ConfigSyncLogResponse.from(new ConfigSyncLogRow(
        11L, "ta", "IMPORT", null, null, null, 5, 5, 0, 0, "SUCCESS", null, null, createdAt));

    assertThat(response.createdAt()).isEqualTo(createdAt);
  }
}
