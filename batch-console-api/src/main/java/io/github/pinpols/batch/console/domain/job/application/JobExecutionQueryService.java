package io.github.pinpols.batch.console.domain.job.application;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.enums.SystemPipelineDefinition;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.file.FilePipelineProgressSnapshotService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.JobExecutionProgressResponse.Stage;
import io.github.pinpols.batch.console.domain.job.mapper.JobExecutionMapper;
import io.github.pinpols.batch.console.domain.job.view.JobExecutionView;
import io.github.pinpols.batch.console.shared.progress.PipelineStepProgressSnapshot;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在租户快照内拼合作业冻结阶段与当前运行尝试进度；未知版本保留已观测阶段，不推测流程图。 */
@Service
@RequiredArgsConstructor
public class JobExecutionQueryService {
  private final TenantIdResolver tenantGuard;
  private final JobExecutionMapper mapper;
  private final FilePipelineProgressSnapshotService filePipelineProgress;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobExecutionProgressResponse query(String requestedTenant, Long id) {
    String resolvedTenant = tenantGuard.resolveTenant(requestedTenant);
    String requiredTenant = TenantScope.requireTenant(resolvedTenant);
    return queryAuthorized(requiredTenant, id);
  }

  /** 后台推送只接受已在订阅入口授权的身份；事务内仍设置RLS租户。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobExecutionProgressResponse queryAuthorized(String tenant, Long id) {
    TenantScope.requireTenant(tenant);
    mapper.setTenantContext(tenant);
    JobExecutionView job = mapper.selectJob(tenant, id);
    if (EmptyChecks.isNull(job))
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.instance_not_found");
    Optional<SystemPipelineDefinition> definition = SystemPipelineCatalog.definitions().stream()
        .filter(d -> Objects.equals(d.code(), job.getSystemPipelineCode())
            && Objects.equals(d.semanticVersion(), job.getSystemPipelineVersion()))
        .findFirst();
    boolean terminal = JobInstanceStatus.terminalCodes().contains(job.getInstanceStatus());
    List<Stage> persisted = EmptyChecks.isNull(job.getPipelineInstanceId())
        ? List.<Stage>of()
        : mapper.selectStages(tenant, job.getPipelineInstanceId());
    List<Stage> runtime = terminal || EmptyChecks.isNull(job.getPipelineInstanceId())
        ? persisted
        : withLiveProgress(tenant, job.getPipelineInstanceId(), persisted);
    definition = definition.filter(
        d -> runtime.stream().allMatch(stage -> d.stages().contains(stage.stageCode())));
    Map<String, Stage> byCode =
        runtime.stream().collect(Collectors.toMap(Stage::stageCode, Function.identity()));
    List<Stage> stages = definition
        .map(d -> d.stages().stream()
            .map(code -> byCode.getOrDefault(
                code,
                new Stage(
                    code, terminal ? "NOT_EXECUTED" : "PENDING", null, null, null, null, null)))
            .toList())
        .orElse(runtime);
    return JobExecutionProgressResponse.builder()
        .tenantId(tenant)
        .jobInstanceId(id)
        .jobCode(job.getJobCode())
        .instanceStatus(job.getInstanceStatus())
        .generatedAt(BatchDateTimeSupport.utcNow())
        .startedAt(job.getStartedAt())
        .finishedAt(job.getFinishedAt())
        .expectedPartitions(job.getExpectedPartitions())
        .workflowRunId(job.getWorkflowRunId())
        .nodeCode(job.getNodeCode())
        .pipelineInstanceId(job.getPipelineInstanceId())
        .pipelineStatus(job.getPipelineStatus())
        .currentStage(job.getCurrentStage())
        .systemPipelineCode(job.getSystemPipelineCode())
        .systemPipelineVersion(job.getSystemPipelineVersion())
        .stageGraphAvailable(definition.isPresent())
        .counts(mapper.selectCounts(tenant, id))
        .stages(stages)
        .build();
  }

  private List<Stage> withLiveProgress(String tenant, Long pipelineId, List<Stage> stages) {
    if (stages.stream()
        .noneMatch(s -> "RUNNING".equals(s.stageStatus()) && EmptyChecks.isNull(s.rowsProcessed())))
      return stages;
    List<PipelineStepProgressSnapshot> liveSteps =
        filePipelineProgress.stepsAuthorized(tenant, pipelineId);
    if (EmptyChecks.isEmpty(liveSteps)) return stages;
    Map<Long, PipelineStepProgressSnapshot> byId = liveSteps.stream()
        .collect(Collectors.toMap(PipelineStepProgressSnapshot::stepId, Function.identity()));
    return stages.stream()
        .map(stage -> {
          PipelineStepProgressSnapshot sample = byId.get(stage.stepRunId());
          boolean sameAttempt = EmptyChecks.isNotNull(sample)
              && Objects.equals(sample.runSeq(), stage.runSeq())
              && "RUNNING".equals(sample.stepStatus());
          if (!"RUNNING".equals(stage.stageStatus())
              || EmptyChecks.isNotNull(stage.rowsProcessed())
              || !sameAttempt) return stage;
          Instant heartbeat = EmptyChecks.isNull(sample.lastHeartbeatAt())
              ? stage.lastHeartbeatAt()
              : Instant.ofEpochMilli(sample.lastHeartbeatAt());
          return new Stage(
              stage.stageCode(),
              stage.stageStatus(),
              stage.stepRunId(),
              stage.runSeq(),
              sample.rowsProcessed(),
              heartbeat,
              stage.errorCode());
        })
        .toList();
  }
}
