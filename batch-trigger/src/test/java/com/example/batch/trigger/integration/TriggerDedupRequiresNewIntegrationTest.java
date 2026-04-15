package com.example.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.SystemException;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.trigger.BatchTriggerApplication;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.service.TriggerService;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * REQUIRES_NEW 事务边界集成测试 — Trigger 去重。
 *
 * <p>{@code DefaultTriggerService#persistAndForward} 使用 {@code
 * TransactionDefinition.PROPAGATION_REQUIRES_NEW} 在独立事务中写入 trigger_request，
 * 然后在外层发起 orchestrator RPC。验证：当 RPC 失败时，PENDING 行仍持久化（可对账检测）。
 */
@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082"
    })
@Import(TriggerDedupRequiresNewIntegrationTest.TestConfig.class)
class TriggerDedupRequiresNewIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TriggerService triggerService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private OrchestratorTriggerAdapter orchestratorTriggerAdapter;

  @BeforeEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.trigger_request");
  }

  @Test
  void pendingRowPersistsEvenWhenOrchestratorRpcFails() {
    // orchestrator RPC 抛异常
    when(orchestratorTriggerAdapter.sendTrigger(any()))
        .thenThrow(new SystemException("orchestrator unreachable"));

    TriggerLaunchCommand command = buildCommand("DEDUP_RPC_FAIL", "idem-rpc-fail", "req-rpc-fail");

    assertThatThrownBy(() -> triggerService.launch(command))
        .isInstanceOf(SystemException.class);

    // REQUIRES_NEW 内的 INSERT 应已独立提交：行存在
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            Integer.class,
            "t1",
            "idem-rpc-fail");
    assertThat(count)
        .as("REQUIRES_NEW should persist row despite RPC failure")
        .isEqualTo(1);

    // RPC 失败后 catch 块将状态从 PENDING 更新为 REJECTED（在 REQUIRES_NEW 外）
    String status =
        jdbcTemplate.queryForObject(
            "select request_status from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            String.class,
            "t1",
            "idem-rpc-fail");
    assertThat(status)
        .as("row should be REJECTED after RPC failure (PENDING → REJECTED in catch block)")
        .isEqualTo("REJECTED");
  }

  @Test
  void dedupCheckPreventsSecondInsertInNewTransaction() {
    when(orchestratorTriggerAdapter.sendTrigger(any()))
        .thenReturn(new LaunchResponse("inst-001", "trace-dedup"));

    TriggerLaunchCommand first = buildCommand("DEDUP_TWICE", "idem-twice", "req-first");
    TriggerLaunchCommand second = buildCommand("DEDUP_TWICE", "idem-twice", "req-second");

    triggerService.launch(first);
    triggerService.launch(second);

    // 两次调用相同 dedupKey，只产生一条记录
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?",
            Integer.class,
            "t1",
            "idem-twice");
    assertThat(count).isEqualTo(1);
  }

  private TriggerLaunchCommand buildCommand(String jobCode, String dedupKey, String requestId) {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode(jobCode);
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of());
    return new TriggerLaunchCommand(request, dedupKey, requestId, "trace-dedup");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
