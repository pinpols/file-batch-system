package io.github.pinpols.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("冻结定义的计划构建:固定路由和分区策略,拒绝串租户或作业身份")
class FrozenSchedulePlanDefinitionTest {

  @Mock private OrchestratorConfigCacheService cache;
  private DefaultSchedulePlanBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new DefaultSchedulePlanBuilder(cache, List.of(new ExplicitPartitionCountResolver()));
  }

  @Test
  @DisplayName("已选择的定义决定分区和路由,不再查询当前作业定义")
  void shouldBuildFromSelectedDefinition_whenExecutionVersionIsFrozen() {
    JobDefinitionEntity frozen = definition("ta", "JOB_A");
    SchedulePlan plan = builder.build(new SchedulePlanCommand(
        "ta", "JOB_A", "2026-01-15", Map.of(), frozen));

    assertThat(plan.getJobDefinitionId()).isEqualTo(10L);
    assertThat(plan.getPartitionCount()).isEqualTo(2);
    assertThat(plan.getQueueCode()).isEqualTo("original_queue");
    assertThat(plan.getWorkerGroup()).isEqualTo("ORIGINAL_GROUP");
    assertThat(plan.getPriority()).isEqualTo(7);
    verify(cache, never()).findEnabledJobDefinition(anyString(), anyString());
  }

  @ParameterizedTest
  @CsvSource({"other_tenant,JOB_A", "ta,OTHER_JOB"})
  @DisplayName("冻结定义不属于请求租户或作业时拒绝构建")
  void shouldRejectMismatchedDefinition_whenTenantOrJobDiffers(String tenant, String job) {
    SchedulePlanCommand command = new SchedulePlanCommand(
        "ta", "JOB_A", "2026-01-15", Map.of(), definition(tenant, job));

    assertThatThrownBy(() -> builder.build(command)).isInstanceOf(BizException.class);
    verify(cache, never()).findEnabledJobDefinition(anyString(), anyString());
  }

  private static JobDefinitionEntity definition(String tenant, String job) {
    return JobDefinitionEntity.builder()
        .id(10L).tenantId(tenant).jobCode(job).jobType("GENERAL")
        .shardStrategy("STATIC").defaultParams(Map.of("partitionCount", 2))
        .queueCode("original_queue").workerGroup("ORIGINAL_GROUP").priority(7)
        .version(1).enabled(true).build();
  }
}
