package io.github.pinpols.batch.orchestrator.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DefaultSchedulePlanBuilderTest {

  private OrchestratorConfigCacheService configCacheService;
  private WorkerRegistryMapper workerRegistryMapper;
  private DefaultSchedulePlanBuilder builder;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger builderLogger;

  @BeforeEach
  void setUp() {
    configCacheService = mock(OrchestratorConfigCacheService.class);
    workerRegistryMapper = mock(WorkerRegistryMapper.class);
    List<PartitionCountResolver> resolvers =
        List.of(
            new BundlePartitionCountResolver(),
            new ExplicitPartitionCountResolver(),
            new SizeBasedPartitionCountResolver(),
            new RuntimeBasedPartitionCountResolver(),
            new WorkerBasedPartitionCountResolver(workerRegistryMapper));
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
    JobDefinitionEntity jobDef = jobDef("NONE", 8, null);
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString())).thenReturn(jobDef);
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPriority()).isEqualTo(8);
  }

  // --- ADR-046 文件束:异构 partition 展开 ---

  @Test
  void shouldExpandBundleJobIntoHeterogeneousPartitions() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 101, "templateCode", "TPL_ORDER"),
                Map.of(
                    "sourceFileId", 102, "templateCode", "TPL_CUST", "targetRef", "biz.customer")));
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(2);
    assertThat(plan.getPartitions()).hasSize(2);
    SchedulePlan.PartitionPlan p1 = plan.getPartitions().get(0);
    assertThat(p1.getSourceFileId()).isEqualTo(101L);
    assertThat(p1.getTemplateCode()).isEqualTo("TPL_ORDER");
    assertThat(p1.getTargetRef()).isNull();
    SchedulePlan.PartitionPlan p2 = plan.getPartitions().get(1);
    assertThat(p2.getSourceFileId()).isEqualTo(102L);
    assertThat(p2.getTemplateCode()).isEqualTo("TPL_CUST");
    assertThat(p2.getTargetRef()).isEqualTo("biz.customer");
  }

  @Test
  void shouldExpandExportBundleByTemplateWithoutSourceFile() {
    // BUNDLE_EXPORT:导出无源文件,各 partition 绑导出模板(=源表/查询),sourceFileId 为空。
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_EXPORT"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("templateCode", "EXP_RISK"),
                Map.of("templateCode", "EXP_TRADE", "targetRef", "sftp-a")));
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitions()).hasSize(2);
    assertThat(plan.getPartitions().get(0).getSourceFileId()).isNull();
    assertThat(plan.getPartitions().get(0).getTemplateCode()).isEqualTo("EXP_RISK");
    assertThat(plan.getPartitions().get(1).getTemplateCode()).isEqualTo("EXP_TRADE");
    assertThat(plan.getPartitions().get(1).getTargetRef()).isEqualTo("sftp-a");
  }

  @Test
  void shouldExpandDispatchBundleByFileAndChannelWithoutTemplate() {
    // BUNDLE_DISPATCH:分发无模板,各 partition 绑待分发文件 + 下游渠道(targetRef)。
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_DISPATCH"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 501, "targetRef", "CH_SFTP"),
                Map.of("sourceFileId", 502, "targetRef", "CH_OSS")));
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitions()).hasSize(2);
    assertThat(plan.getPartitions().get(0).getSourceFileId()).isEqualTo(501L);
    assertThat(plan.getPartitions().get(0).getTargetRef()).isEqualTo("CH_SFTP");
    assertThat(plan.getPartitions().get(0).getTemplateCode()).isNull();
    assertThat(plan.getPartitions().get(1).getSourceFileId()).isEqualTo(502L);
    assertThat(plan.getPartitions().get(1).getTargetRef()).isEqualTo("CH_OSS");
  }

  @Test
  void shouldFailFastWhenBundleCountMismatchesPartitionCount() {
    // 束作业配成 NONE 策略 → partitionCount=1,但 bundleFiles 有 2 个 → 配置错,fail-fast 不静默丢文件
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("NONE", 5));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params =
        Map.of(
            "bundleFiles",
            List.of(
                Map.of("sourceFileId", 101, "templateCode", "TPL_A"),
                Map.of("sourceFileId", 102, "templateCode", "TPL_B")));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(command(params)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bundle partition count mismatch");
  }

  @Test
  void shouldFailFastWhenBundleJobHasNoUsableBinding() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of("bundleFiles", List.of(Map.of("sourceFileId", 101)));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(command(params)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bundleFiles required");
  }

  @Test
  void shouldNotBindFilesForNonBundleJob() {
    // 非束作业即便 params 误带 bundleFiles 也不绑定(jobType 不是 BUNDLE_IMPORT)
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params =
        Map.of(
            "partitionCount",
            1,
            "bundleFiles",
            List.of(Map.of("sourceFileId", 101, "templateCode", "TPL_A")));
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitions().get(0).getSourceFileId()).isNull();
    assertThat(plan.getPartitions().get(0).getTemplateCode()).isNull();
  }

  // --- helpers ---

  private static SchedulePlanCommand command(Map<String, Object> params) {
    return new SchedulePlanCommand("t1", "JOB_001", "2026-01-01", params);
  }

  private static JobDefinitionEntity bundleJobDef(String shardStrategy, int priority) {
    return bundleJobDef(shardStrategy, priority, "BUNDLE_IMPORT");
  }

  private static JobDefinitionEntity bundleJobDef(
      String shardStrategy, int priority, String jobType) {
    return new JobDefinitionEntity(
        1L,
        "t1",
        "JOB_001",
        null,
        jobType,
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
        null,
        null,
        true,
        null,
        null,
        null);
  }

  private static JobDefinitionEntity jobDef(
      String shardStrategy, int priority, Map<String, Object> defaultParams) {
    return new JobDefinitionEntity(
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
