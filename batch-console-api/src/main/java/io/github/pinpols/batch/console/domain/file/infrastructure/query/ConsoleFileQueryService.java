package io.github.pinpols.batch.console.domain.file.infrastructure.query;

import static io.github.pinpols.batch.console.shared.query.ConsoleQuerySupport.*;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.enums.PipelineRunStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.SystemPipelineCatalog;
import io.github.pinpols.batch.common.enums.SystemPipelineDefinition;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.ContentMaskingUtils;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.SensitiveEvidenceMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.query.FileChainQueryRequest;
import io.github.pinpols.batch.console.application.file.FilePipelineProgressSnapshotService;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileArrivalGroupQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileChannelQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileDispatchRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileErrorRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FilePipelineQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FilePipelineStepQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileTemplateQueryRequest;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileArrivalGroupResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileChannelResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileDispatchRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileErrorRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineProgressResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineStepProgressResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineStepResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileProjectionMapper;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileRecordDetailResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileRecordResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileSummaryResponse;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFileTemplateResponse;
import io.github.pinpols.batch.console.domain.file.application.progress.PipelineProgressResponse.Pipeline;
import io.github.pinpols.batch.console.domain.file.entity.FileArrivalGroupEntity;
import io.github.pinpols.batch.console.domain.file.entity.FileErrorRecordEntity;
import io.github.pinpols.batch.console.domain.file.entity.FileRecordEntity;
import io.github.pinpols.batch.console.domain.file.query.FileArrivalGroupQuery;
import io.github.pinpols.batch.console.domain.file.query.FileChannelConfigQuery;
import io.github.pinpols.batch.console.domain.file.query.FileDispatchRecordQuery;
import io.github.pinpols.batch.console.domain.file.query.FileErrorRecordQuery;
import io.github.pinpols.batch.console.domain.file.query.FilePipelineQuery;
import io.github.pinpols.batch.console.domain.file.query.FilePipelineStepRunQuery;
import io.github.pinpols.batch.console.domain.file.query.FileRecordQuery;
import io.github.pinpols.batch.console.domain.file.query.FileTemplateConfigQuery;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileDispatchRecordQueryView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineExecutionView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineInfoView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineProgressStepView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineQueryView;
import io.github.pinpols.batch.console.domain.file.view.FilePipelineStepRunQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileRecordStorageView;
import io.github.pinpols.batch.console.domain.file.view.FileRecordSummaryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateConfigQueryView;
import io.github.pinpols.batch.console.domain.file.view.FileTemplateSecurityFlagsView;
import io.github.pinpols.batch.console.shared.progress.PipelineStepProgressSnapshot;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.shared.query.TenantScope;
import io.github.pinpols.batch.console.shared.view.ConsolePipelineProgressItemResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文件相关查询子服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleFileQueryService implements FilePipelineProgressSnapshotService {

  // 查询条件字段名常量
  private static final String KEY_FROM_TIME = "fromTime";
  private static final String KEY_TO_TIME = "toTime";

  private static final String STEP_STATUS_RUNNING = "RUNNING";

  private final TenantIdResolver tenantGuard;
  private final ConsoleFileQueryMappers fileMappers;
  private final BatchSecurityProperties batchSecurityProperties;
  private final LocalizedErrorRenderer localizedErrorRenderer;
  private final ConsoleOrchestratorPort orchestratorProxy;
  private final BatchDateTimeSupport dateTimeSupport;

  public PageResponse<ConsoleFileRecordResponse> fileChains(FileChainQueryRequest request) {
    boolean cursorMode = EmptyChecks.isNotNull(request.getCursor());
    PageRequest pageRequest = cursorMode
        ? new PageRequest(1, request.getPageSize())
        : new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    boolean usePipelineTypeFallback = !Texts.hasText(request.getBizType());
    String bizType = usePipelineTypeFallback ? request.getPipelineType() : request.getBizType();
    Long fileId = parseLong(request.getFileId(), "fileId");
    LocalDate startDate = parseLocalDate(request.getStartDate(), "startDate");
    LocalDate endDate = parseLocalDate(request.getEndDate(), "endDate");
    Instant fromTime = parseInstant(request.getFromTime(), KEY_FROM_TIME);
    Instant toTime = parseInstant(request.getToTime(), KEY_TO_TIME);
    if (Boolean.TRUE.equals(request.getArrivalToday())) {
      ZoneId zone = dateTimeSupport.defaultBusinessZone();
      LocalDate today = dateTimeSupport.todayInDefaultBusinessZone();
      Instant dayStart = today.atStartOfDay(zone).toInstant();
      Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1_000);
      if (EmptyChecks.isNull(fromTime) || fromTime.isBefore(dayStart)) {
        fromTime = dayStart;
      }
      if (EmptyChecks.isNull(toTime) || toTime.isAfter(dayEnd)) {
        toTime = dayEnd;
      }
    }
    Long cursorId = decodeCursorId(request.getCursor());
    FileRecordQuery query = FileRecordQuery.builder()
        .tenantId(tenantId)
        .bizType(bizType)
        .fileStatus(request.getFileStatus())
        .fileId(fileId)
        .fileName(request.getFileName())
        .jobCode(request.getJobCode())
        .traceId(request.getTraceId())
        .startDate(startDate)
        .endDate(endDate)
        .fromTime(fromTime)
        .toTime(toTime)
        .pageRequest(pageRequest)
        .cursorId(cursorId)
        .build();
    List<FileRecordEntity> rows = fileMappers.fileRecordMapper.selectByQuery(query);
    if (cursorMode) {
      return cursorPage(pageRequest, rows, this::toFileRecordResponse, FileRecordEntity::getId);
    }
    long total = fileMappers.fileRecordMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toFileRecordResponse);
  }

  /** 文件列表统计：今日到达、待处理、已处理和失败数量。 */
  public ConsoleFileSummaryResponse fileSummary(String requestTenantId) {
    String tenantId = resolveTenant(tenantGuard, requestTenantId);
    FileRecordSummaryView summary = fileMappers.fileRecordMapper.selectSummary(tenantId);
    if (EmptyChecks.isNull(summary)) {
      return new ConsoleFileSummaryResponse(0L, 0L, 0L, 0L);
    }
    long arrivedToday = summaryCount(summary.arrivedToday());
    long pending = summaryCount(summary.pending());
    long processed = summaryCount(summary.processed());
    long failed = summaryCount(summary.failed());
    return new ConsoleFileSummaryResponse(arrivedToday, pending, processed, failed);
  }

  private static long summaryCount(Long value) {
    return EmptyChecks.isNull(value) ? 0L : value;
  }

  public PageResponse<ConsoleFilePipelineResponse> filePipelines(FilePipelineQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FilePipelineQuery.FilePipelineQueryBuilder queryBuilder = FilePipelineQuery.builder()
        .tenantId(tenantId)
        .keyword(request.getKeyword())
        .fileId(request.getFileId())
        .pipelineInstanceId(request.getPipelineInstanceId())
        .pipelineType(request.getPipelineType())
        .runStatus(request.getRunStatus())
        .traceId(request.getTraceId());
    Instant fromTime = parseInstant(request.getFromTime(), KEY_FROM_TIME);
    queryBuilder.fromTime(fromTime);
    Instant toTime = parseInstant(request.getToTime(), KEY_TO_TIME);
    queryBuilder.toTime(toTime);
    FilePipelineQuery q = queryBuilder.pageRequest(pageRequest).build();
    List<FilePipelineQueryView> rows = fileMappers.filePipelineMapper.selectByQuery(q);
    long total = fileMappers.filePipelineMapper.countByQuery(q.withoutPage());
    return page(pageRequest, total, rows, this::toFilePipelineResponse);
  }

  public PageResponse<ConsoleFilePipelineStepResponse> filePipelineSteps(
      FilePipelineStepQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FilePipelineStepRunQuery query = FilePipelineStepRunQuery.builder()
        .tenantId(tenantId)
        .keyword(request.getKeyword())
        .pipelineInstanceId(request.getPipelineInstanceId())
        .stepCode(request.getStepCode())
        .stageCode(request.getStageCode())
        .stepStatus(request.getStepStatus())
        .pageRequest(pageRequest)
        .build();
    List<FilePipelineStepRunQueryView> rows =
        fileMappers.filePipelineStepRunMapper.selectByQuery(query);
    long total = fileMappers.filePipelineStepRunMapper.countByQuery(query.withoutPage());
    return page(pageRequest, total, rows, this::toFilePipelineStepResponse);
  }

  public ConsoleFilePipelineProgressResponse pipelineProgress(Long pipelineInstanceId) {
    if (EmptyChecks.isNull(pipelineInstanceId) || pipelineInstanceId <= 0) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.field.must_be_number", "pipelineInstanceId");
    }
    String tenantId = fileMappers.filePipelineStepRunMapper.selectTenantIdByPipelineInstanceId(
        pipelineInstanceId);
    if (EmptyChecks.isNull(tenantId)) {
      return new ConsoleFilePipelineProgressResponse(pipelineInstanceId, null, null, List.of());
    }
    tenantGuard.assertTenantAllowed(tenantId);
    List<FilePipelineProgressStepView> rawSteps =
        fileMappers.filePipelineStepRunMapper.selectProgressByPipelineInstance(
            tenantId, pipelineInstanceId);
    return assemblePipelineProgress(tenantId, pipelineInstanceId, rawSteps, true);
  }

  /** 仅供已授权范围的只读事务调用；不依赖请求线程SecurityContext。 */
  public Pipeline pipelineSnapshotAuthorized(String tenantId, Long pipelineInstanceId) {
    TenantScope.requireTenant(tenantId);
    FilePipelineExecutionView execution =
        fileMappers.filePipelineStepRunMapper.selectExecution(tenantId, pipelineInstanceId);
    if (EmptyChecks.isNull(execution))
      throw BizException.of(
          ResultCode.NOT_FOUND, "error.pipeline.instance_not_found", pipelineInstanceId);
    List<FilePipelineProgressStepView> rawSteps =
        fileMappers.filePipelineStepRunMapper.selectLatestProgressByPipelineInstance(
            tenantId, pipelineInstanceId);
    boolean terminal = List.of(
            PipelineRunStatus.SUCCESS.code(),
            PipelineRunStatus.FAILED.code(),
            PipelineRunStatus.TERMINATED.code())
        .contains(execution.getRunStatus());
    ConsoleFilePipelineProgressResponse progress =
        assemblePipelineProgress(tenantId, pipelineInstanceId, rawSteps, !terminal);
    Optional<SystemPipelineDefinition> catalog = SystemPipelineCatalog.definitions().stream()
        .filter(d -> Objects.equals(d.code(), execution.getSystemPipelineCode())
            && Objects.equals(d.semanticVersion(), execution.getSystemPipelineVersion()))
        .filter(
            d -> progress.steps().stream().allMatch(step -> d.stages().contains(step.stageCode())))
        .findFirst();
    List<String> stageCodes = catalog.map(SystemPipelineDefinition::stages).orElse(List.of());
    return new Pipeline(
        pipelineInstanceId,
        progress.fileId(),
        progress.fileName(),
        execution.getRunStatus(),
        execution.getCurrentStage(),
        execution.getSystemPipelineCode(),
        execution.getSystemPipelineVersion(),
        catalog.isPresent(),
        stageCodes,
        progress.steps());
  }

  @Override
  public List<PipelineStepProgressSnapshot> stepsAuthorized(
      String tenantId, Long pipelineInstanceId) {
    Pipeline liveSnapshot = pipelineSnapshotAuthorized(tenantId, pipelineInstanceId);
    List<ConsoleFilePipelineStepProgressResponse> stepProgress = liveSnapshot.steps();
    List<PipelineStepProgressSnapshot> snapshots =
        stepProgress.stream().map(this::toProgressSnapshot).toList();
    return snapshots;
  }

  private PipelineStepProgressSnapshot toProgressSnapshot(
      ConsoleFilePipelineStepProgressResponse step) {
    return new PipelineStepProgressSnapshot(
        step.stepId(),
        step.runSeq(),
        step.stepStatus(),
        step.rowsProcessed(),
        step.lastHeartbeatAt());
  }

  private ConsoleFilePipelineProgressResponse assemblePipelineProgress(
      String tenantId,
      Long pipelineInstanceId,
      List<FilePipelineProgressStepView> rawSteps,
      boolean allowLive) {
    if (EmptyChecks.isNull(rawSteps)) {
      rawSteps = List.of();
    }
    // 运行中步骤尚无持久化处理行数时，从当前 Worker 的进度缓存补充实时值。
    // 前端仍按 pipelineInstanceId 查询，Worker 解析和进度合并均在服务端完成。
    Map<String, ConsolePipelineProgressItemResponse> liveProgress =
        allowLive ? resolveLiveProgress(tenantId, pipelineInstanceId, rawSteps) : Map.of();
    List<ConsoleFilePipelineStepProgressResponse> steps = rawSteps.stream()
        .map(row -> toFilePipelineStepProgressResponse(row, liveProgress))
        .toList();
    // 文件名属于流水线响应级字段；一个流水线实例至多关联一条文件记录。
    FilePipelineInfoView fileInfo =
        fileMappers.filePipelineStepRunMapper.selectFileInfoByPipelineInstance(
            tenantId, pipelineInstanceId);
    Long fileId = EmptyChecks.isNull(fileInfo) ? null : fileInfo.fileId();
    String fileName = EmptyChecks.isNull(fileInfo) ? null : display(fileInfo.fileName());
    return new ConsoleFilePipelineProgressResponse(pipelineInstanceId, fileId, fileName, steps);
  }

  /** 仅当运行中步骤的持久化处理行数为空时，才查询编排器节点缓存。 */
  private Map<String, ConsolePipelineProgressItemResponse> resolveLiveProgress(
      String tenantId, Long pipelineInstanceId, List<FilePipelineProgressStepView> rawSteps) {
    if (EmptyChecks.isEmpty(rawSteps)) {
      return Map.of();
    }
    boolean needsBridge = rawSteps.stream()
        .anyMatch(row -> EmptyChecks.isNotNull(row)
            && STEP_STATUS_RUNNING.equals(display(row.stepStatus()))
            && EmptyChecks.isNull(row.rowsProcessed()));
    if (!needsBridge) {
      return Map.of();
    }
    List<ConsolePipelineProgressItemResponse> cache =
        orchestratorProxy.pipelineProgressByInstance(tenantId, pipelineInstanceId);
    if (EmptyChecks.isEmpty(cache)) {
      return Map.of();
    }
    Map<String, ConsolePipelineProgressItemResponse> progressByStage = cache.stream()
        .filter(EmptyChecks::isNotNull)
        .filter(item -> EmptyChecks.isNotNull(item.stageCode()))
        .collect(Collectors.toUnmodifiableMap(
            ConsolePipelineProgressItemResponse::stageCode,
            Function.identity(),
            (first, ignored) -> first));
    return progressByStage;
  }

  public PageResponse<ConsoleFileDispatchRecordResponse> fileDispatchRecords(
      FileDispatchRecordQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    Instant fromTime = parseInstant(request.getFromTime(), KEY_FROM_TIME);
    Instant toTime = parseInstant(request.getToTime(), KEY_TO_TIME);
    FileDispatchRecordQuery q = new FileDispatchRecordQuery(
        tenantId,
        request.getKeyword(),
        request.getFileId(),
        request.getChannelCode(),
        request.getDispatchStatus(),
        request.getReceiptStatus(),
        fromTime,
        toTime,
        pageRequest);
    List<FileDispatchRecordQueryView> rows = fileMappers.fileDispatchRecordMapper.selectByQuery(q);
    long total = fileMappers.fileDispatchRecordMapper.countByQuery(q.withoutPage());
    return page(pageRequest, total, rows, this::toFileDispatchRecordResponse);
  }

  public PageResponse<ConsoleFileChannelResponse> fileChannels(FileChannelQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String queryTenantId = resolveTenant(tenantGuard, request.getTenantId());
    FileChannelConfigQuery fileChannelConfigQuery = FileChannelConfigQuery.builder()
        .tenantId(queryTenantId)
        .channelCode(request.getChannelCode())
        .channelType(request.getChannelType())
        .enabled(request.getEnabled())
        .pageRequest(pageRequest)
        .build();
    List<FileChannelConfigQueryView> rows =
        fileMappers.fileChannelConfigMapper.selectByQuery(fileChannelConfigQuery);
    String countTenantId = resolveTenant(tenantGuard, request.getTenantId());
    long total = fileMappers.fileChannelConfigMapper.countByQuery(
        countTenantId, request.getChannelCode(), request.getChannelType(), request.getEnabled());
    return page(pageRequest, total, rows, ConsoleFileProjectionMapper::channelView);
  }

  public PageResponse<ConsoleFileTemplateResponse> fileTemplates(FileTemplateQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String tenantId = resolveTenant(tenantGuard, request.getTenantId());
    FileTemplateConfigQuery q = FileTemplateConfigQuery.builder()
        .tenantId(tenantId)
        .keyword(request.getKeyword())
        .templateCode(request.getTemplateCode())
        .templateName(request.getTemplateName())
        .templateType(request.getTemplateType())
        .bizType(request.getBizType())
        .enabled(request.getEnabled())
        .pageRequest(pageRequest)
        .build();
    List<FileTemplateConfigQueryView> rows = fileMappers.fileTemplateConfigMapper.selectByQuery(q);
    long total = fileMappers.fileTemplateConfigMapper.countByQuery(q);
    return page(pageRequest, total, rows, ConsoleFileProjectionMapper::templateView);
  }

  public PageResponse<ConsoleFileArrivalGroupResponse> fileArrivalGroups(
      FileArrivalGroupQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String resolvedTenantId = resolveTenant(tenantGuard, request.getTenantId());
    String requiredTenantId = TenantScope.requireTenant(resolvedTenantId);
    FileArrivalGroupQuery query = FileArrivalGroupQuery.ofState(
        requiredTenantId, request.getFileGroupCode(), request.getArrivalState(), pageRequest);
    List<FileArrivalGroupEntity> rows = fileMappers.fileArrivalGroupMapper.selectByQuery(query);
    long total = fileMappers.fileArrivalGroupMapper.countByQuery(query);
    return page(pageRequest, total, rows, this::toFileArrivalGroupResponse);
  }

  public PageResponse<ConsoleFileErrorRecordResponse> fileErrorRecords(
      FileErrorRecordQueryRequest request) {
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    String resolvedTenantId = resolveTenant(tenantGuard, request.getTenantId());
    FileErrorRecordQuery query = new FileErrorRecordQuery(
        resolvedTenantId,
        request.getKeyword(),
        request.getFileId(),
        request.getErrorStage(),
        request.getErrorCode(),
        request.getSkipped(),
        pageRequest);
    List<FileErrorRecordEntity> rows = fileMappers.fileErrorRecordMapper.selectByQuery(query);
    long total = fileMappers.fileErrorRecordMapper.countByQuery(query);
    Map<Long, Optional<FileTemplateSecurityFlagsView>> policies = new HashMap<>();
    return page(
        pageRequest,
        total,
        rows,
        row -> toFileErrorRecordResponse(
            row, fileContentMaskingPolicy(query.tenantId(), row.getFileId(), policies)));
  }

  public ConsoleFileChannelResponse fileChannelDetail(String tenantId, String channelCode) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    FileChannelConfigQueryView row =
        fileMappers.fileChannelConfigMapper.selectByUniqueKey(resolved, channelCode);
    if (EmptyChecks.isNull(row)) {
      throw BizException.of(
          ResultCode.NOT_FOUND,
          ResultCode.NOT_FOUND.detailKey(),
          "file channel not found: " + channelCode);
    }
    return ConsoleFileProjectionMapper.channelView(row);
  }

  public ConsoleFileTemplateResponse fileTemplateDetail(
      String tenantId, String templateCode, Integer version) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    Integer ver = EmptyChecks.isNotNull(version) ? version : 1;
    FileTemplateConfigQueryView row =
        fileMappers.fileTemplateConfigMapper.selectByUniqueKey(resolved, templateCode, ver);
    if (EmptyChecks.isNull(row)) {
      throw BizException.of(
          ResultCode.NOT_FOUND,
          ResultCode.NOT_FOUND.detailKey(),
          "file template not found: " + templateCode);
    }
    return ConsoleFileProjectionMapper.templateView(row);
  }

  public ConsoleFileRecordDetailResponse fileRecordDetail(String tenantId, Long fileId) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    FileRecordStorageView fileRecord =
        fileMappers.fileRecordMapper.selectFileRecordById(resolved, fileId);
    if (EmptyChecks.isNull(fileRecord)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found", fileId);
    }
    return ConsoleFileRecordDetailResponse.from(fileRecord);
  }

  public ConsoleFilePipelineResponse filePipelineDetail(String tenantId, Long id) {
    String resolved = resolveTenant(tenantGuard, tenantId);
    PageRequest pageSingle = new PageRequest(1, 1);
    FilePipelineQuery detailQuery = FilePipelineQuery.ofPipeline(resolved, id, pageSingle);
    List<FilePipelineQueryView> rows = fileMappers.filePipelineMapper.selectByQuery(detailQuery);
    if (EmptyChecks.isEmpty(rows) || EmptyChecks.isNull(rows.get(0))) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.pipeline.instance_not_found", id);
    }
    return toFilePipelineResponse(rows.get(0));
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (EmptyChecks.isNull(value)) {
      return false;
    }
    return "true".equalsIgnoreCase(String.valueOf(value));
  }

  private FileTemplateSecurityFlagsView fileContentMaskingPolicy(
      String tenantId, Long fileId, Map<Long, Optional<FileTemplateSecurityFlagsView>> policies) {
    boolean fileMaskingRequestIsIneligible = batchSecurityProperties.isBypassMode()
        || EmptyChecks.isNull(fileId)
        || !Texts.hasText(tenantId);
    if (fileMaskingRequestIsIneligible) {
      return null;
    }
    return policies
        .computeIfAbsent(fileId, id -> Optional.ofNullable(findFileContentPolicy(tenantId, id)))
        .orElse(null);
  }

  private FileTemplateSecurityFlagsView findFileContentPolicy(String tenantId, Long fileId) {
    String templateCode = fileMappers.fileRecordMapper.selectTemplateCodeByFileId(tenantId, fileId);
    if (!Texts.hasText(templateCode)) {
      return null;
    }
    return fileMappers.fileTemplateConfigMapper.selectSecurityFlagsByTemplateCode(
        tenantId, templateCode);
  }

  private ConsoleFileRecordResponse toFileRecordResponse(FileRecordEntity entity) {
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    String bizType = display(entity.getBizType());
    String fileName = display(entity.getFileName());
    String fileStatus = display(entity.getFileStatus());
    LocalDate bizDate = entity.getBizDate();
    String traceId = display(entity.getTraceId());
    Instant createdAt = entity.getCreatedAt();
    Instant updatedAt = entity.getUpdatedAt();
    return new ConsoleFileRecordResponse(
        id, tenantId, bizType, fileName, fileStatus, bizDate, traceId, createdAt, updatedAt);
  }

  private ConsoleFilePipelineResponse toFilePipelineResponse(FilePipelineQueryView row) {
    ConsoleFilePipelineResponse response = ConsoleFilePipelineResponse.builder()
        .id(row.id())
        .tenantId(display(row.tenantId()))
        .jobCode(display(row.jobCode()))
        .pipelineType(display(row.pipelineType()))
        .fileId(row.fileId())
        .relatedJobInstanceId(row.relatedJobInstanceId())
        .currentStage(display(row.currentStage()))
        .lastSuccessStage(display(row.lastSuccessStage()))
        .runStatus(display(row.runStatus()))
        .traceId(display(row.traceId()))
        .startedAt(row.startedAt())
        .finishedAt(row.finishedAt())
        .createdAt(row.createdAt())
        .updatedAt(row.updatedAt())
        .build();
    return response;
  }

  private ConsoleFilePipelineStepResponse toFilePipelineStepResponse(
      FilePipelineStepRunQueryView row) {
    if (EmptyChecks.isNull(row)) {
      ConsoleFilePipelineStepResponse emptyResponse = ConsoleFilePipelineStepResponse.builder()
          .errorMessage(renderLocalizedError((Map<String, Object>) null))
          .build();
      return emptyResponse;
    }
    Long id = row.id();
    Long pipelineInstanceId = row.pipelineInstanceId();
    String stepCode = display(row.stepCode());
    String stageCode = display(row.stageCode());
    Integer runSeq = row.runSeq();
    String stepStatus = display(row.stepStatus());
    String inputSummary = maskedJsonValue(row.inputSummary());
    String outputSummary = maskedJsonValue(row.outputSummary());
    String errorCode = display(row.errorCode());
    String errorMessage = SensitiveEvidenceMasking.maskText(renderLocalizedError(
        display(row.errorKey()), row.errorArgs(), display(row.errorMessage())));
    Integer retryCount = row.retryCount();
    Long durationMs = row.durationMs();
    Instant startedAt = row.startedAt();
    Instant finishedAt = row.finishedAt();
    ConsoleFilePipelineStepResponse response = ConsoleFilePipelineStepResponse.builder()
        .id(id)
        .pipelineInstanceId(pipelineInstanceId)
        .stepCode(stepCode)
        .stageCode(stageCode)
        .runSeq(runSeq)
        .stepStatus(stepStatus)
        .inputSummary(inputSummary)
        .outputSummary(outputSummary)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .retryCount(retryCount)
        .durationMs(durationMs)
        .startedAt(startedAt)
        .finishedAt(finishedAt)
        .build();
    return response;
  }

  private String maskedJsonValue(Map<String, Object> row, String key) {
    Object rawValue = EmptyChecks.isNull(row) ? null : row.get(key);
    return maskedJsonValue(rawValue);
  }

  private String maskedJsonValue(Object rawValue) {
    if (EmptyChecks.isNull(rawValue)) {
      return null;
    }
    Object parsed = JsonUtils.fromJson(String.valueOf(rawValue), Object.class);
    String masked = JsonUtils.toJson(SensitiveEvidenceMasking.maskJsonValue(parsed));
    return ConsoleTextSanitizer.safeDisplay(masked);
  }

  private ConsoleFilePipelineStepProgressResponse toFilePipelineStepProgressResponse(
      FilePipelineProgressStepView row,
      Map<String, ConsolePipelineProgressItemResponse> liveProgress) {
    Instant lastHeartbeatAt = row.lastHeartbeatAt();
    ConsolePipelineProgressItemResponse live = liveProgress.get(display(row.stageCode()));
    // 跨服务查询不共享数据库快照；必须再次核对缓存来源阶段ID，避免新尝试计数污染旧快照。
    boolean sameAttempt = EmptyChecks.isNotNull(live)
        && EmptyChecks.isNotEmpty(live.stepRunIds())
        && live.stepRunIds().contains(row.stepId());
    Long rowsProcessed = row.rowsProcessed();
    Long totalRowsHint = row.totalRowsHint();
    boolean running = STEP_STATUS_RUNNING.equals(display(row.stepStatus()));
    boolean shouldBridgeLiveRowsProcessed = running
        && sameAttempt
        && EmptyChecks.isNull(rowsProcessed)
        && EmptyChecks.isNotNull(live)
        && EmptyChecks.isNotNull(live.rowsProcessed());
    if (shouldBridgeLiveRowsProcessed) {
      rowsProcessed = live.rowsProcessed();
      if (EmptyChecks.isNull(lastHeartbeatAt)) {
        lastHeartbeatAt = live.heartbeatAt();
      }
    }
    boolean shouldBridgeLiveTotalRowsHint = running
        && sameAttempt
        && EmptyChecks.isNull(totalRowsHint)
        && EmptyChecks.isNotNull(live)
        && EmptyChecks.isNotNull(live.totalRowsHint());
    if (shouldBridgeLiveTotalRowsHint) {
      totalRowsHint = live.totalRowsHint();
    }
    Long stepId = row.stepId();
    Long pipelineInstanceId = row.pipelineInstanceId();
    String stepCode = display(row.stepCode());
    String stageCode = display(row.stageCode());
    Long heartbeatEpochMillis =
        EmptyChecks.isNull(lastHeartbeatAt) ? null : lastHeartbeatAt.toEpochMilli();
    return new ConsoleFilePipelineStepProgressResponse(
        stepId,
        pipelineInstanceId,
        stepCode,
        stageCode,
        rowsProcessed,
        totalRowsHint,
        heartbeatEpochMillis,
        row.stepStatus(),
        row.runSeq(),
        row.errorCode());
  }

  private ConsoleFileDispatchRecordResponse toFileDispatchRecordResponse(
      FileDispatchRecordQueryView row) {
    if (EmptyChecks.isNull(row)) {
      ConsoleFileDispatchRecordResponse emptyResponse = ConsoleFileDispatchRecordResponse.builder()
          .errorMessage(renderLocalizedError((Map<String, Object>) null))
          .build();
      return emptyResponse;
    }
    Long id = row.id();
    String tenantId = display(row.tenantId());
    Long fileId = row.fileId();
    Long pipelineInstanceId = row.pipelineInstanceId();
    String channelCode = display(row.channelCode());
    String dispatchTarget = display(SensitiveEvidenceMasking.maskText(row.dispatchTarget()));
    String dispatchStatus = display(row.dispatchStatus());
    Integer dispatchAttempt = row.dispatchAttempt();
    String receiptCode = display(row.receiptCode());
    String receiptStatus = display(row.receiptStatus());
    String externalRequestId = display(row.externalRequestId());
    String errorCode = display(row.errorCode());
    String errorMessage = SensitiveEvidenceMasking.maskText(renderLocalizedError(
        display(row.errorKey()), row.errorArgs(), display(row.errorMessage())));
    Instant dispatchedAt = row.dispatchedAt();
    Instant ackAt = row.ackAt();
    Instant createdAt = row.createdAt();
    Instant updatedAt = row.updatedAt();
    ConsoleFileDispatchRecordResponse response = ConsoleFileDispatchRecordResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .fileId(fileId)
        .pipelineInstanceId(pipelineInstanceId)
        .channelCode(channelCode)
        .dispatchTarget(dispatchTarget)
        .dispatchStatus(dispatchStatus)
        .dispatchAttempt(dispatchAttempt)
        .receiptCode(receiptCode)
        .receiptStatus(receiptStatus)
        .externalRequestId(externalRequestId)
        .errorCode(errorCode)
        .errorMessage(errorMessage)
        .dispatchedAt(dispatchedAt)
        .ackAt(ackAt)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
    return response;
  }

  /**
   * i18n 持久化:行有 error_key 时按当前 Locale 重渲染,否则透传 error_message。 用于所有 resultType="map" 行携带
   * (error_key/error_args/error_message) 三元组的场景。
   */
  private String renderLocalizedError(Map<String, Object> row) {
    String errorKey = stringValue(row, "error_key");
    String rawErrorArgs = rawValue(row, "error_args");
    String maskedErrorArgs = SecretMasking.maskSensitiveJson(rawErrorArgs);
    String errorMessage = stringValue(row, "error_message");
    return localizedErrorRenderer.render(errorKey, maskedErrorArgs, errorMessage);
  }

  private String renderLocalizedError(String errorKey, Object errorArgs, String errorMessage) {
    String rawErrorArgs = EmptyChecks.isNull(errorArgs) ? null : String.valueOf(errorArgs);
    String maskedErrorArgs = SecretMasking.maskSensitiveJson(rawErrorArgs);
    return localizedErrorRenderer.render(errorKey, maskedErrorArgs, errorMessage);
  }

  private String rawValue(Map<String, Object> row, String key) {
    Object value = EmptyChecks.isNull(row) ? null : row.get(key);
    return EmptyChecks.isNull(value) ? null : String.valueOf(value);
  }

  private ConsoleFileArrivalGroupResponse toFileArrivalGroupResponse(
      FileArrivalGroupEntity entity) {
    String tenantId = display(entity.getTenantId());
    String fileGroupCode = display(entity.getFileGroupCode());
    String waitFileGroupMode = display(entity.getWaitFileGroupMode());
    String requiredFileSet = display(entity.getRequiredFileSet());
    String arrivalTimeoutAction = display(entity.getArrivalTimeoutAction());
    String arrivalState = display(entity.getArrivalState());
    Instant expectedArrivalTime = entity.getExpectedArrivalTime();
    Instant latestTolerableTime = entity.getLatestTolerableTime();
    Long arrivedCount = entity.getArrivedCount();
    Long triggeredCount = entity.getTriggeredCount();
    Long timeoutCount = entity.getTimeoutCount();
    Long waitingCount = entity.getWaitingCount();
    Instant lastUpdatedAt = entity.getLastUpdatedAt();
    return ConsoleFileArrivalGroupResponse.builder()
        .tenantId(tenantId)
        .fileGroupCode(fileGroupCode)
        .waitFileGroupMode(waitFileGroupMode)
        .requiredFileSet(requiredFileSet)
        .arrivalTimeoutAction(arrivalTimeoutAction)
        .arrivalState(arrivalState)
        .expectedArrivalTime(expectedArrivalTime)
        .latestTolerableTime(latestTolerableTime)
        .arrivedCount(arrivedCount)
        .triggeredCount(triggeredCount)
        .timeoutCount(timeoutCount)
        .waitingCount(waitingCount)
        .lastUpdatedAt(lastUpdatedAt)
        .build();
  }

  private ConsoleFileErrorRecordResponse toFileErrorRecordResponse(
      FileErrorRecordEntity entity, FileTemplateSecurityFlagsView policy) {
    String errorMessage = localizedErrorRenderer.render(entity);
    String rawContent = entity.getRawRecord();
    if (EmptyChecks.isNotNull(policy)) {
      boolean maskErrors = truthy(policy.errorLineMaskingEnabled());
      if (maskErrors && EmptyChecks.isNotNull(errorMessage)) {
        errorMessage = ContentMaskingUtils.maskPlainText(errorMessage, policy.maskingRuleSet());
      }
      if ((maskErrors || truthy(policy.previewMaskingEnabled()))
          && EmptyChecks.isNotNull(rawContent)) {
        rawContent = ContentMaskingUtils.maskPlainText(rawContent, policy.maskingRuleSet());
      }
    }
    Long id = entity.getId();
    String tenantId = display(entity.getTenantId());
    Long fileId = entity.getFileId();
    Long pipelineInstanceId = entity.getPipelineInstanceId();
    Long pipelineStepRunId = entity.getPipelineStepRunId();
    Long recordNo = entity.getRecordNo();
    String errorCode = display(entity.getErrorCode());
    String displayedErrorMessage = display(SensitiveEvidenceMasking.maskText(errorMessage));
    String errorStage = display(entity.getErrorStage());
    Boolean skipped = entity.getSkipped();
    String skipAction = display(entity.getSkipAction());
    String rawRecord = display(rawContent);
    Instant createdAt = entity.getCreatedAt();
    return ConsoleFileErrorRecordResponse.builder()
        .id(id)
        .tenantId(tenantId)
        .fileId(fileId)
        .pipelineInstanceId(pipelineInstanceId)
        .pipelineStepRunId(pipelineStepRunId)
        .recordNo(recordNo)
        .errorCode(errorCode)
        .errorMessage(displayedErrorMessage)
        .errorStage(errorStage)
        .skipped(skipped)
        .skipAction(skipAction)
        .rawRecord(rawRecord)
        .createdAt(createdAt)
        .build();
  }
}
