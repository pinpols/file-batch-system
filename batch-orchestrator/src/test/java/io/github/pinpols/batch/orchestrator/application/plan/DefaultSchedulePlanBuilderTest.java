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
import io.github.pinpols.batch.orchestrator.config.PersistenceGranularityProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("调度计划构建器: 分区数量解析, 束作业展开与准入参数装配口径")
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
    List<PartitionCountResolver> resolvers = List.of(
        new BundlePartitionCountResolver(),
        new ExplicitPartitionCountResolver(),
        new SizeBasedPartitionCountResolver(new PersistenceGranularityProperties()),
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
  @DisplayName("作业定义缺失时退化为单分区计划并套用默认优先级")
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
  @DisplayName("不切分策略下只生成一个分区")
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
  @DisplayName("静态策略下按参数给出的数量生成分区")
  void shouldUseStaticPartitionCountFromParams() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of("partitionCount", 4)));

    assertThat(plan.getPartitionCount()).isEqualTo(4);
    assertThat(plan.getPartitions()).hasSize(4);
  }

  @Test
  @DisplayName("静态策略缺少参数时回退为单分区")
  void shouldFallbackToOnePartitionWhenStaticParamsMissing() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPartitionCount()).isEqualTo(1);
  }

  // --- DYNAMIC shard strategy ---

  @Test
  @DisplayName("动态策略有数据量估算时按大小解析分区数量")
  void shouldUseSizeBasedPartitionCountForDynamicStrategy() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 1000 items / 100 per partition = 10 partitions
    Map<String, Object> params = Map.of(
        "estimatedItemCount", 1000,
        "targetItemsPerPartition", 100);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(10);
    assertThat(plan.getPartitions()).hasSize(10);
  }

  @Test
  @DisplayName("动态策略缺少数据量估算时按历史时长解析分区数量")
  void shouldUseRuntimeBasedPartitionCountWhenSizeNotAvailable() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 300 seconds historical / 60 seconds target = 5 partitions (ceil)
    Map<String, Object> params = Map.of(
        "historicalAverageDurationSeconds", 300,
        "targetPartitionDurationSeconds", 60);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("请求的分区数量超过上限时收敛到允许的最大值")
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
  @DisplayName("显式数量与大小解析同时可用时显式优先, 并记录被覆盖的解析结果")
  void shouldLogShadowedResolverWhenExplicitOverridesSize() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    // 同时给 explicit (=3) 和 size-resolvable (=10) 两组参数 → explicit 赢,size 应被 INFO 记录覆盖
    Map<String, Object> params = Map.of(
        "partitionCount", 3,
        "estimatedItemCount", 1000,
        "targetItemsPerPartition", 100);
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitionCount()).isEqualTo(3);
    assertThat(logAppender.list).anySatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.INFO);
      assertThat(event.getFormattedMessage())
          .contains("partition count resolver overridden")
          .contains("ExplicitPartitionCountResolver")
          .contains("SizeBasedPartitionCountResolver");
    });
  }

  @Test
  @DisplayName("只有一个解析器产出数量时不记录覆盖日志")
  void shouldNotLogShadowWhenOnlyOneResolverProducesValue() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("DYNAMIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of("partitionCount", 3);
    builder.build(command(params));

    assertThat(logAppender.list)
        .noneSatisfy(event -> assertThat(event.getFormattedMessage())
            .contains("partition count resolver overridden"));
  }

  // --- partition key format ---

  @Test
  @DisplayName("分区键按任务编码, 营业日与序号拼接, 业务键不带序号")
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

  @Test
  @DisplayName("按期望行数均分生成确定性的分片范围契约")
  void shouldPopulateDeterministicPartitionContractFromExpectedRows() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of("partitionCount", 3, "expectedRows", 10)));

    assertThat(plan.getTotalExpectedRows()).isEqualTo(10L);
    assertThat(plan.getPartitions()).hasSize(3);
    SchedulePlan.PartitionPlan first = plan.getPartitions().get(0);
    assertThat(first.getShardIndex()).isZero();
    assertThat(first.getShardTotal()).isEqualTo(3);
    assertThat(first.getRangeStartInclusive()).isZero();
    assertThat(first.getRangeEndExclusive()).isEqualTo(3L);
    assertThat(first.getExpectedRows()).isEqualTo(3L);
    SchedulePlan.PartitionPlan last = plan.getPartitions().get(2);
    assertThat(last.getShardIndex()).isEqualTo(2);
    assertThat(last.getShardTotal()).isEqualTo(3);
    assertThat(last.getRangeStartInclusive()).isEqualTo(6L);
    assertThat(last.getRangeEndExclusive()).isEqualTo(10L);
    assertThat(last.getExpectedRows()).isEqualTo(4L);
  }

  // --- priority inheritance ---

  @Test
  @DisplayName("计划优先级继承作业定义中的配置")
  void shouldInheritPriorityFromJobDefinition() {
    JobDefinitionEntity jobDef = jobDef("NONE", 8, null);
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString())).thenReturn(jobDef);
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of()));

    assertThat(plan.getPriority()).isEqualTo(8);
  }

  // --- ADR-046 文件束:异构 partition 展开 ---

  @Test
  @DisplayName("束作业按文件清单展开为各自绑定的分区")
  void shouldExpandBundleJobIntoHeterogeneousPartitions() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 101, "templateCode", "TPL_ORDER"),
            Map.of("sourceFileId", 102, "templateCode", "TPL_CUST", "targetRef", "biz.customer")));
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
  @DisplayName("导出束按模板展开分区, 允许没有源文件")
  void shouldExpandExportBundleByTemplateWithoutSourceFile() {
    // BUNDLE_EXPORT:导出无源文件,各 partition 绑导出模板(=源表/查询),sourceFileId 为空。
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_EXPORT"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of(
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
  @DisplayName("分发束按待分发文件与下游渠道展开分区, 不绑定模板")
  void shouldExpandDispatchBundleByFileAndChannelWithoutTemplate() {
    // BUNDLE_DISPATCH:分发无模板,各 partition 绑待分发文件 + 下游渠道(targetRef)。
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_DISPATCH"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of(
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
  @DisplayName("束文件数量与分区数量不一致时快速失败, 不静默丢文件")
  void shouldFailFastWhenBundleCountMismatchesPartitionCount() {
    // 束作业配成 NONE 策略 → partitionCount=1,但 bundleFiles 有 2 个 → 配置错,fail-fast 不静默丢文件
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("NONE", 5));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 101, "templateCode", "TPL_A"),
            Map.of("sourceFileId", 102, "templateCode", "TPL_B")));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build(command(params)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bundle partition count mismatch");
  }

  @Test
  @DisplayName("束作业没有任何可用绑定时快速失败")
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
  @DisplayName("非束作业即使参数误带束文件清单也不做绑定")
  void shouldNotBindFilesForNonBundleJob() {
    // 非束作业即便 params 误带 bundleFiles 也不绑定(jobType 不是 BUNDLE_IMPORT)
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(jobDef("STATIC", 5, null));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    Map<String, Object> params = Map.of(
        "partitionCount",
        1,
        "bundleFiles",
        List.of(Map.of("sourceFileId", 101, "templateCode", "TPL_A")));
    SchedulePlan plan = builder.build(command(params));

    assertThat(plan.getPartitions().get(0).getSourceFileId()).isNull();
    assertThat(plan.getPartitions().get(0).getTemplateCode()).isNull();
  }

  @Test
  @DisplayName("资源画像与下游渠道一路带入准入计划与准入请求")
  void shouldCarryResourceProfileAndDispatchChannelIntoAdmissionPlan() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_DISPATCH"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of(
        "resourceProfile",
        "io-heavy",
        "channelCode",
        "SFTP_SETTLEMENT",
        "bundleFiles",
        List.of(Map.of("sourceFileId", 501, "targetRef", "SFTP_SETTLEMENT")))));

    assertThat(plan.getResourceProfile()).isEqualTo("io-heavy");
    assertThat(plan.getDownstreamChannelCode()).isEqualTo("SFTP_SETTLEMENT");
    ResourceSchedulingRequest request = SchedulePlanSupport.toSchedulingRequest(plan);
    assertThat(request.getResourceProfile()).isEqualTo("io-heavy");
    assertThat(request.getDownstreamChannelCode()).isEqualTo("SFTP_SETTLEMENT");
    assertThat(request.getDownstreamChannelCodes()).containsExactly("SFTP_SETTLEMENT");
  }

  @Test
  @DisplayName("分发束的每个下游渠道都进入准入请求的渠道清单")
  void shouldCarryEveryDispatchBundleTargetIntoAdmissionRequest() {
    when(configCacheService.findEnabledJobDefinition(anyString(), anyString()))
        .thenReturn(bundleJobDef("DYNAMIC", 5, "BUNDLE_DISPATCH"));
    when(configCacheService.findEnabledWorkflowDefinition(any(), any())).thenReturn(null);

    SchedulePlan plan = builder.build(command(Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 501, "targetRef", "CH_SFTP"),
            Map.of("sourceFileId", 502, "targetRef", "CH_OSS")))));

    ResourceSchedulingRequest request = SchedulePlanSupport.toSchedulingRequest(plan);

    assertThat(request.getDownstreamChannelCodes()).containsExactly("CH_SFTP", "CH_OSS");
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
