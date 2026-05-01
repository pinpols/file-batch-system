package com.example.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import com.example.batch.orchestrator.repository.WorkerRegistryRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DefaultSchedulePlanBuilderTest {

  private OrchestratorConfigCacheService configCacheService;
  private WorkerRegistryRepository workerRegistryRepository;
  private DefaultSchedulePlanBuilder builder;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger builderLogger;

  @BeforeEach
  void setUp() {
    configCacheService = mock(OrchestratorConfigCacheService.class);
    workerRegistryRepository = mock(WorkerRegistryRepository.class);
    List<PartitionCountResolver> resolvers =
        List.of(
            new ExplicitPartitionCountResolver(),
            new SizeBasedPartitionCountResolver(),
            new RuntimeBasedPartitionCountResolver(),
            new WorkerBasedPartitionCountResolver(workerRegistryRepository));
    builder = new DefaultSchedulePlanBuilder(configCacheService, resolvers);

    builderLogger = (Logger) LoggerFactory.getLogger(DefaultSchedulePlanBuilder.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    builderLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    if (builderLogger != null && logAppender != null) {
      builderLogger.detachAppender(logAppender);
    }
  }

  // --- null / missing job definition ---

  @Test
  void shouldBuildPlanWithSinglePartitionWhenJobDefinitionMissing() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString())).thenReturn(null);
    when(configCacheService.findEnabledWorkflowDefinition(anyString(), anyString()))
        .thenReturn(null);

    SchedulePlanCommand command = new SchedulePlanCommand("t1", "JOB_001", "2026-01-01", Map.of());
    SchedulePlan plan = builder.build(command);

    assertThat(plan.getTenantId()).isEqualTo("t1");
    assertThat(plan.getJobCode()).isEqualTo("JOB_001");
    assertThat(plan.getPartitions()).hasSize(1);
    assertThat(plan.getPartitions().get(0).getPartitionNo()).isEqualTo(1);
    assertThat(plan.getPriority()).isEqualTo(5);
  }

  // --- NONE shard strategy ---

  @Test
  void shouldProduceSinglePartitionForNoneStrategy() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("NONE", 3, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPartitionCount()).isEqualTo(1);
    assertThat(plan.getPartitions()).hasSize(1);
  }

  // --- STATIC shard strategy ---

  @Test
  void shouldUseStaticPartitionCountFromParams() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of("partitionCount", 4)));

    assertThat(plan.getPartitionCount()).isEqualTo(4);
    assertThat(plan.getPartitions()).hasSize(4);
  }

  @Test
  void shouldFallbackToOnePartitionWhenStaticParamsMissing() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPartitionCount()).isEqualTo(1);
  }

  // --- DYNAMIC shard strategy ---

  @Test
  void shouldUseSizeBasedPartitionCountForDynamicStrategy() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 1000 items / 100 per partition = 10 partitions
    Map<String, Object> params =
        Map.of(
            "estimatedItemCount", 1000,
            "targetItemsPerPartition", 100);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(10);
    assertThat(plan.getPartitions()).hasSize(10);
  }

  @Test
  void shouldUseRuntimeBasedPartitionCountWhenSizeNotAvailable() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 300 seconds historical / 60 seconds target = 5 partitions (ceil)
    Map<String, Object> params =
        Map.of(
            "historicalAverageDurationSeconds", 300,
            "targetPartitionDurationSeconds", 60);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(5);
  }

  @Test
  void shouldCapPartitionCountAtMaxLimit() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // requested 300 but max is 256
    Map<String, Object> params = Map.of("partitionCount", 300);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isLessThanOrEqualTo(256);
  }

  // --- resolver chain shadow logging (2026-05-01 hardening) ---

  @Test
  void shouldLogShadowedResolverWhenExplicitOverridesSize() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 同时给 explicit (=3) 和 size-resolvable (=10) 两组参数 → explicit 赢,size 应被 INFO 记录覆盖
    Map<String, Object> params =
        Map.of(
            "partitionCount", 3,
            "estimatedItemCount", 1000,
            "targetItemsPerPartition", 100);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(3);
    assertThat(logAppender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .contains("partition count resolver overridden")
                  .contains("ExplicitPartitionCountResolver")
                  .contains("SizeBasedPartitionCountResolver");
            });
  }

  @Test
  void shouldNotLogShadowWhenOnlyOneResolverProducesValue() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of("partitionCount", 3);
    builder.build(command(params));

    assertThat(logAppender.list)
        .noneSatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("partition count resolver overridden"));
  }

  // --- partition key format ---

  @Test
  void shouldGenerateCorrectPartitionKeyFormat() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlanCommand cmd =
        new SchedulePlanCommand("t1", "JOB_001", "2026-01-01", Map.of("partitionCount", 2));
    SchedulePlan plan = builder.build(cmd);

    assertThat(plan.getPartitions().get(0).getPartitionKey()).isEqualTo("JOB_001:2026-01-01:1");
    assertThat(plan.getPartitions().get(1).getPartitionKey()).isEqualTo("JOB_001:2026-01-01:2");
    assertThat(plan.getPartitions().get(0).getBusinessKey()).isEqualTo("JOB_001:2026-01-01");
  }

  // --- priority inheritance ---

  @Test
  void shouldInheritPriorityFromJobDefinition() {
    JobDefinitionRecord jobDef = jobDef("NONE", 8, null);
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString())).thenReturn(jobDef);
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPriority()).isEqualTo(8);
  }

  // --- helpers ---

  private static SchedulePlanCommand command(Map<String, Object> params) {
    return new SchedulePlanCommand("t1", "JOB_001", "2026-01-01", params);
  }

  private static JobDefinitionRecord jobDef(
      String shardStrategy, int priority, Map<String, Object> defaultParams) {
    return new JobDefinitionRecord(
        1L,
        "t1",
        "JOB_001",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        shardStrategy,
        null,
        null,
        null,
        null,
        null,
        priority,
        defaultParams,
        null,
        true,
        null,
        null,
        null);
  }
}
