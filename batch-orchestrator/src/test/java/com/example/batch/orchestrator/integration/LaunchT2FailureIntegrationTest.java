package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.application.service.task.PartitionDispatchService;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import com.example.batch.orchestrator.integration.support.LaunchIntegrationFixture.LaunchSeed;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.service.LaunchService;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 集成测试：{@link LaunchService#launch} 中 T1/T2 事务拆分。
 *
 * <p>场景：T2（{@link PartitionDispatchService#dispatch}）在 T1 （{@code prepareJobInstance}）已提交之后抛出异常。验证：
 *
 * <ol>
 *   <li>T1 写入的 {@code job_instance} 行在 T2 失败后依然存在。
 *   <li>不存在 {@code job_partition} 行（T2 已完整回滚）。
 *   <li>使用相同 {@code requestId} 的后续 {@code launch()} 命中去重路径， 返回已创建的实例而不再调用 T2。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LaunchT2FailureIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);

  @Autowired private LaunchService launchService;

  @Autowired private JobInstanceMapper jobInstanceMapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private PartitionDispatchService partitionDispatchService;

  @Test
  void t1CommitsAndDedupWorksWhenT2Throws() {
    LaunchSeed seed =
        LaunchIntegrationFixture.prepareLaunchWithWorker(
            jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.MANUAL);

    doThrow(new RuntimeException("simulated T2 failure"))
        .when(partitionDispatchService)
        .dispatch(any());

    LaunchRequest request =
        new LaunchRequest(
            TENANT,
            seed.jobCode(),
            BIZ_DATE,
            TriggerType.MANUAL,
            seed.requestId(),
            "trace-t2-failure-test",
            Map.of());

    // 第一次调用：T2 失败 —— 异常从 launch() 传播出去
    assertThatThrownBy(() -> launchService.launch(request))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("simulated T2 failure");

    // T1 committed: job_instance must exist in DB
    JobInstanceEntity jobInstance =
        jobInstanceMapper.selectByTenantAndDedupKey(TENANT, seed.dedupKey());
    assertThat(jobInstance)
        .as("job_instance should be committed by T1 even though T2 failed")
        .isNotNull();

    // T2 rolled back: no job_partition rows for this instance
    Long partitionCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_partition where job_instance_id = ?",
            Long.class,
            jobInstance.getId());
    assertThat(partitionCount)
        .as("no job_partition rows should exist when T2 rolled back")
        .isZero();

    // T2 rolled back: no job_task rows for this instance
    Long taskCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch.job_task where job_instance_id = ?",
            Long.class,
            jobInstance.getId());
    assertThat(taskCount).as("no job_task rows should exist when T2 rolled back").isZero();

    // 使用相同 requestId 的第二次调用：走去重路径 —— T2 不会再被调用
    LaunchResponse retryResponse = launchService.launch(request);
    assertThat(retryResponse.instanceNo())
        .as("retry should return the instance created by the first T1")
        .isEqualTo(jobInstance.getInstanceNo());
  }
}
