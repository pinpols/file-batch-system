package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link TriggerRequestMapper} 的 nullable 参数和状态 CAS 真实 PostgreSQL 回归测试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TriggerRequestMapperIntegrationTest extends AbstractIntegrationTest {

  private final String tenantId = "trigger-mapper-" + Long.toUnsignedString(System.nanoTime());
  private final String requestId = "request-" + Long.toUnsignedString(System.nanoTime());

  private final TriggerRequestMapper triggerRequestMapper;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  TriggerRequestMapperIntegrationTest(
      TriggerRequestMapper triggerRequestMapper, JdbcTemplate jdbcTemplate) {
    this.triggerRequestMapper = triggerRequestMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update(
        "delete from batch.trigger_request where tenant_id = ? and request_id = ?",
        tenantId,
        requestId);
  }

  @Test
  void updateAcceptanceAcceptsNullRelatedJobInstanceId() {
    jdbcTemplate.update("""
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, dedup_key, request_status, trace_id
        ) values (?, ?, 'MANUAL', 'mapper-null-regression', ?, 'ACCEPTED', 'trace-mapper-null')
        """, tenantId, requestId, "dedup-" + requestId);

    int updated = triggerRequestMapper.updateAcceptance(
        tenantId, requestId, TriggerRequestStatus.REJECTED.code(), null);

    TriggerRequestEntity request =
        triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
    assertThat(updated).isOne();
    assertThat(request.getRequestStatus()).isEqualTo(TriggerRequestStatus.REJECTED.code());
    assertThat(request.getRelatedJobInstanceId()).isNull();
  }
}
