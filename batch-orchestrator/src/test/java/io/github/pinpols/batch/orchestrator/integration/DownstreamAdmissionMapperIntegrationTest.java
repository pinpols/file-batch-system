package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.DownstreamAdmissionMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;

/** 真 PostgreSQL 验证多渠道准入查询，防止束分发只检查首个目标或发生跨租户误阻断。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("下游准入查询组件,验证多渠道请求中任一渠道不健康即判定阻断以及租户之间的数据隔离")
class DownstreamAdmissionMapperIntegrationTest extends AbstractIntegrationTest {

  private final String tenant = "downstream-admission-" + System.nanoTime();

  private final DownstreamAdmissionMapper downstreamAdmissionMapper;
  private final JdbcTemplate jdbcTemplate;

  DownstreamAdmissionMapperIntegrationTest(
      DownstreamAdmissionMapper downstreamAdmissionMapper, JdbcTemplate jdbcTemplate) {
    this.downstreamAdmissionMapper = downstreamAdmissionMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Test
  @DisplayName("请求的多个渠道中任一处于不健康状态时,应判定为需要阻断")
  void shouldReportBlocked_whenAnyRequestedChannelUnhealthy() {
    insertHealth(tenant, "SFTP_A", "HEALTHY");
    insertHealth(tenant, "OSS_B", "UNHEALTHY");

    boolean blocked =
        downstreamAdmissionMapper.isAnyDispatchChannelBlocked(tenant, List.of("SFTP_A", "OSS_B"));

    assertThat(blocked).isTrue();
  }

  @Test
  @DisplayName("不健康记录仅属于其它租户时,不应阻断当前租户的渠道请求")
  void shouldIgnoreUnhealthyRowsFromAnotherTenant() {
    insertHealth(tenant + "-other", "SFTP_A", "UNHEALTHY");

    boolean blocked =
        downstreamAdmissionMapper.isAnyDispatchChannelBlocked(tenant, List.of("SFTP_A"));

    assertThat(blocked).isFalse();
  }

  private void insertHealth(String tenantId, String channelCode, String status) {
    jdbcTemplate.update(
        "insert into batch.file_channel_health "
            + "(tenant_id, channel_code, channel_type, health_status) values (?, ?, 'SFTP', ?)",
        tenantId,
        channelCode,
        status);
  }
}
