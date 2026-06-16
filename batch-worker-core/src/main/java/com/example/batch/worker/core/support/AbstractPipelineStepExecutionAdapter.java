package com.example.batch.worker.core.support;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.rls.RlsTenantContextHolder;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.domain.PipelineStepTemplate;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 三条 worker 链路（import / export / dispatch）共用的 pipeline 生命周期模板： 确保 pipeline 定义存在 → 创建 pipeline 实例 →
 * 执行各阶段 → 标记实例最终状态。
 *
 * <p><b>执行流程（{@link #execute} → {@link #doExecute}）</b>：
 *
 * <ol>
 *   <li>通过 {@link PlatformFileRuntimeRepository#ensurePipelineDefinition} 查找或自动创建 pipeline
 *       定义（首次运行可无需手动在控制台配置）。
 *   <li>创建本次执行的 pipeline 实例（{@code pipeline_instance}），写入初始阶段和 traceId。
 *   <li>将 pipelineDefinitionId、pipelineInstanceId、stepDefinitions 注入 {@code attributes}， 再构建业务上下文
 *       {@code C}，调用子类 {@link #executeStages(Object)} 执行所有阶段。
 *   <li>全部阶段成功 → {@code markPipelineSuccess}；任一阶段失败 → {@code markPipelineFailed}，
 *       记录最后一个成功阶段，供运维定位断点。
 *   <li>任何未捕获异常同样标记 pipeline 失败，不向上透传（转为 {@link StepExecutionResponse} 返回）。
 * </ol>
 *
 * <p><b>子类约定</b>：
 *
 * <ul>
 *   <li>必须实现 {@link #pipelineType} / {@link #defaultPipelineSteps} 等描述性方法。
 *   <li>{@link #executeStages} 内部应捕获阶段级异常并返回失败结果，不得上抛。
 *   <li>{@link #handlePipelineFailure} 用于在 pipeline 失败后触发补偿（如错误文件上传、状态回写）。
 * </ul>
 */
public abstract class AbstractPipelineStepExecutionAdapter<C extends ExecutionContext, R>
    implements StepExecutionAdapter {

  private static final ObjectMapper ERROR_OBJECT_MAPPER = new ObjectMapper();

  private final PlatformFileRuntimeRepository runtimeRepository;

  /**
   * ADR-030 §C: 可选注入。Spring 在 batch-worker-core 上下文里有 PipelineVerifierHook 时由构造器注入; 测试 / 无 hook
   * bean 场景为 null,runVerifierHook() 直接跳过。
   *
   * <p>review 2026-05-21: 之前用 setter + @Autowired(required=false),违反 CLAUDE.md §Java #3 (DI
   * 只用构造器);改为 ObjectProvider 显式构造器注入。
   */
  private final PipelineVerifierHook verifierHook;

  /**
   * 安全增量补偿(opt-in)钩子。可选注入,语义同 {@link #verifierHook}：无 bean(测试 / 未装配)时为 null, {@link
   * #runCompensationIfEnabled} 直接跳过 → 走原 markPipelineFailed 路径(默认行为逐字节不变)。
   */
  private final PipelineCompensationHook compensationHook;

  protected AbstractPipelineStepExecutionAdapter(
      PlatformFileRuntimeRepository runtimeRepository,
      ObjectProvider<PipelineVerifierHook> verifierHookProvider,
      ObjectProvider<PipelineCompensationHook> compensationHookProvider) {
    this.runtimeRepository = runtimeRepository;
    this.verifierHook = verifierHookProvider.getIfAvailable();
    this.compensationHook = compensationHookProvider.getIfAvailable();
  }

  // 不能加 final:Spring CGLIB 用 Objenesis 实例化代理(跳过构造器→ runtimeRepository 字段为 null);
  // 若 execute final, CGLIB 无法 override,代理直接跑 final 方法 → 触发 NPE。@Timed AOP 织入需要可覆盖。
  @Override
  @Timed(
      value = "batch.pipeline.step.execution.duration",
      description = "Worker pipeline step execution latency",
      histogram = true)
  public StepExecutionResponse execute(StepExecutionRequest request) {
    Map<String, Object> sourceAttributes = request.context();
    Map<String, Object> attributes =
        new LinkedHashMap<>(sourceAttributes == null ? Map.of() : sourceAttributes);
    String traceId = resolveTraceId(attributes);
    injectMdc(request, attributes, traceId);
    // Phase A RLS:绑 tenant_id 到 ThreadLocal,plugin / mapper 进 @Transactional 时
    // 由 RlsTenantSessionSupport.applyIfPresent(businessDS) 取出并 SET LOCAL
    // app.tenant_id,触发 biz.* RLS policy 强制隔离。
    RlsTenantContextHolder.set(request.tenantId());
    try {
      return doExecute(request, attributes, traceId);
    } finally {
      propagateRuntimeAttributes(sourceAttributes, attributes);
      RlsTenantContextHolder.clear();
      BatchMdc.remove(StructuredLogField.TENANT_ID);
      BatchMdc.remove(StructuredLogField.TRACE_ID);
      BatchMdc.remove(StructuredLogField.JOB_INSTANCE_ID);
      BatchMdc.remove(StructuredLogField.WORKER_ID);
      BatchMdc.remove(StructuredLogField.RUN_MODE);
    }
  }

  private void propagateRuntimeAttributes(
      Map<String, Object> sourceAttributes, Map<String, Object> runtimeAttributes) {
    if (sourceAttributes == null || sourceAttributes == runtimeAttributes) {
      return;
    }
    try {
      sourceAttributes.putAll(runtimeAttributes);
    } catch (UnsupportedOperationException ignored) {
      SwallowedExceptionLogger.info(
          AbstractPipelineStepExecutionAdapter.class,
          "catch:UnsupportedOperationException",
          ignored);

      // Some unit tests pass immutable maps; production execution contexts are mutable.
    }
  }

  private void injectMdc(
      StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
    BatchMdc.putIfAbsent(StructuredLogField.TENANT_ID, request.tenantId());
    BatchMdc.putIfAbsent(StructuredLogField.TRACE_ID, traceId);
    Object jobInstanceId = attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID);
    if (jobInstanceId != null) {
      BatchMdc.putIfAbsent(StructuredLogField.JOB_INSTANCE_ID, String.valueOf(jobInstanceId));
    }
    BatchMdc.putIfAbsent(StructuredLogField.WORKER_ID, request.workerId());
    String runMode =
        resolveText(attributes, PipelineRuntimeKeys.RUN_MODE, PipelineRuntimeKeys.LEGACY_RUN_MODE);
    BatchMdc.putIfAbsent(StructuredLogField.RUN_MODE, runMode);
  }

  private StepExecutionResponse doExecute(
      StepExecutionRequest request, Map<String, Object> attributes, String traceId) {
    String jobCode = resolveJobCode(request, attributes);
    Long fileId = runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
    Long pipelineDefinitionId =
        runtimeRepository.ensurePipelineDefinition(
            request.tenantId(),
            jobCode,
            pipelineType(),
            pipelineWorkerGroup(),
            pipelineDescription(),
            defaultPipelineSteps());
    if (pipelineDefinitionId == null) {
      BizException exception =
          BizException.of(ResultCode.NOT_FOUND, "error.pipeline.definition_not_found");
      return StepExecutionResponse.failure(exception, ERROR_OBJECT_MAPPER);
    }
    List<PipelineStepDefinition> pipelineSteps =
        runtimeRepository.loadPipelineSteps(pipelineDefinitionId);
    if (pipelineSteps.isEmpty()) {
      BizException exception =
          BizException.of(ResultCode.NOT_FOUND, "error.pipeline.step_definition_missing");
      return StepExecutionResponse.failure(exception, ERROR_OBJECT_MAPPER);
    }
    Long pipelineInstanceId =
        runtimeRepository.createPipelineInstance(
            new PlatformFileRuntimeRepository.CreatePipelineInstanceParam(
                request.tenantId(),
                pipelineDefinitionId,
                jobCode,
                pipelineType(),
                fileId,
                runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID)),
                resolveInitialStage(pipelineSteps),
                traceId));
    attributes.put(PipelineRuntimeKeys.TRACE_ID, traceId);
    attributes.put(PipelineRuntimeKeys.JOB_CODE, jobCode);
    attributes.put(PipelineRuntimeKeys.PIPELINE_DEFINITION_ID, pipelineDefinitionId);
    attributes.put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, pipelineInstanceId);
    attributes.put(PipelineRuntimeKeys.PIPELINE_STEP_DEFINITIONS, pipelineSteps);
    // ADR-026: dry-run 标记从 task payload (orchestrator 写) 提取到 attributes,
    // step plugin 调用 DryRunGuard.fromAttributes(attributes) 拿 guard 包副作用。
    attributes.putIfAbsent(PipelineRuntimeKeys.DRY_RUN, resolveDryRunFromPayload(attributes));
    attributes.putIfAbsent(PipelineRuntimeKeys.JOB_CODE, request.jobCode());
    attributes.putIfAbsent("stepCode", request.stepCode());
    attributes.putIfAbsent(PipelineRuntimeKeys.FILE_ID, fileId);
    if (fileId != null) {
      attributes.put(
          PipelineRuntimeKeys.FILE_RECORD,
          runtimeRepository.loadFileRecord(request.tenantId(), fileId));
    }
    try {
      C context = buildContext(request, attributes, fileId);
      List<R> results = executeStages(context);
      R failed = firstFailure(results);
      if (failed == null) {
        String successStage = lastSuccessfulStage(attributes);
        // 先调 buildSuccessResponse 把 NODE_OUTPUTS 写进 attributes，verifier 才能拿到规范输出。
        // 但不立即返回 / 不立即 markPipelineSuccess —— 给 §G 硬中止 verifier 留翻转机会。
        StepExecutionResponse successResponse = buildSuccessResponse(context, results, attributes);

        // ADR-030 §C/G: 跑 ContentVerifier。
        //  - 软告警（fatal=false）：失败仅落 attributes.verifierFailures，pipeline 继续 SUCCESS
        //  - 硬中止（fatal=true）：把 pipeline 翻为 FAILED，错误码 VERIFIER_FATAL
        PipelineVerifierHook.VerifierHookResult verifierResult =
            verifierHook == null
                ? PipelineVerifierHook.VerifierHookResult.NO_FATAL
                : verifierHook.runVerifiers(
                    request.tenantId(),
                    pipelineType(),
                    runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.JOB_INSTANCE_ID)),
                    runtimeRepository.toLong(attributes.get(PipelineRuntimeKeys.TASK_ID)),
                    successStage,
                    attributes);

        if (verifierResult.fatalFailure()) {
          // §G 硬中止：DB 标 FAILED，返回失败 response 而非 success。
          // 安全增量补偿(opt-in):若开关 on 且注册了 compensator,先 COMPENSATING + 反向动作,再落 FAILED 终态。
          runCompensationIfEnabled(request.tenantId(), pipelineInstanceId, attributes);
          runtimeRepository.markPipelineFailed(
              pipelineInstanceId, successStage, lastSuccessfulStage(attributes));
          String fatalCode =
              verifierResult.firstFatalCode() == null
                  ? "VERIFIER_FATAL"
                  : verifierResult.firstFatalCode();
          String fatalMessage =
              verifierResult.firstFatalMessage() == null
                  ? "ContentVerifier reported fatal failure"
                  : verifierResult.firstFatalMessage();
          handlePipelineFailure(attributes, fatalCode, fatalMessage, null, null);
          return new StepExecutionResponse(false, fatalCode, fatalMessage);
        }

        runtimeRepository.markPipelineSuccess(pipelineInstanceId, successStage, successStage);
        return successResponse;
      }
      String failureStage = resultStage(failed);
      // 安全增量补偿(opt-in):stage 失败落地点。开关 off → 直接 markPipelineFailed(行为不变)。
      runCompensationIfEnabled(request.tenantId(), pipelineInstanceId, attributes);
      runtimeRepository.markPipelineFailed(
          pipelineInstanceId, failureStage, lastSuccessfulStage(attributes));
      handlePipelineFailure(
          attributes,
          resultCode(failed),
          resultMessage(failed),
          resultErrorKey(failed),
          resultErrorArgs(failed));
      return new StepExecutionResponse(
          false,
          resultCode(failed),
          resultMessage(failed),
          resultErrorKey(failed),
          resultErrorArgs(failed));
    } catch (Exception exception) {
      SwallowedExceptionLogger.warn(
          AbstractPipelineStepExecutionAdapter.class, "catch:Exception", exception);

      // 安全增量补偿(opt-in):未捕获异常落地点。开关 off → 直接 markPipelineFailed(行为不变)。
      runCompensationIfEnabled(request.tenantId(), pipelineInstanceId, attributes);
      runtimeRepository.markPipelineFailed(
          pipelineInstanceId, initialStage(), lastSuccessfulStage(attributes));
      if (exception instanceof BizException bizException) {
        StepExecutionResponse failure =
            StepExecutionResponse.failure(bizException, ERROR_OBJECT_MAPPER);
        handlePipelineFailure(
            attributes,
            failure.code(),
            failure.message(),
            failure.getErrorKey(),
            failure.getErrorArgs());
        return failure;
      }
      handlePipelineFailure(attributes, unexpectedErrorCode(), exception.getMessage(), null, null);
      return new StepExecutionResponse(false, unexpectedErrorCode(), exception.getMessage());
    }
  }

  protected PlatformFileRuntimeRepository runtimeRepository() {
    return runtimeRepository;
  }

  /**
   * 安全增量补偿(opt-in)统一入口。在每个失败落地点 markPipelineFailed **之前**调用：
   *
   * <ul>
   *   <li>无 compensation hook bean(测试 / 未装配)→ 直接返回,走原路径(默认行为逐字节不变)。
   *   <li>有 hook → 由 hook 判定开关 {@code compensate_on_failure} + 是否注册了该类型 compensator;只有都满足才进入
   *       COMPENSATING + 反向动作 + 审计。hook 永不抛异常,补偿失败不掩盖原始失败。
   * </ul>
   *
   * <p>本方法无论补偿是否触发都不改变后续 markPipelineFailed 调用 —— COMPENSATING 仅是 hook 内部写的中间态, 终态始终由 adapter 的
   * markPipelineFailed 落定,绝不停在 COMPENSATING。
   */
  private void runCompensationIfEnabled(
      String tenantId, Long pipelineInstanceId, Map<String, Object> attributes) {
    if (compensationHook == null) {
      return;
    }
    compensationHook.runCompensation(tenantId, pipelineType(), pipelineInstanceId, attributes);
  }

  protected String pipelineWorkerGroup() {
    return "worker-" + pipelineType().toLowerCase();
  }

  protected String resolveInitialStage(List<PipelineStepDefinition> pipelineSteps) {
    PipelineStepDefinition firstStep = PipelineStepFlowSupport.firstStep(pipelineSteps);
    return firstStep == null ? initialStage() : firstStep.stageCode();
  }

  protected String unexpectedErrorCode() {
    return pipelineType() + "_PIPELINE_ERROR";
  }

  protected String resolveTraceId(Map<String, Object> attributes) {
    String traceId = resolveText(attributes, PipelineRuntimeKeys.TRACE_ID, "sourceTraceId");
    return Texts.hasText(traceId)
        ? traceId
        : pipelineType().toLowerCase() + "-" + UUID.randomUUID();
  }

  protected String resolveJobCode(StepExecutionRequest request, Map<String, Object> attributes) {
    // 优先级 1：workflow TASK 节点派发时，orchestrator 在 task_payload JSON 里写
    // `targetJobCode` 指向实际要跑的子作业（如 SETTLE 节点 → `exp_settlement_daily`）。
    // 若用 request.jobCode()（= workflow 自己的 jobCode，如 `wf_eod_process`），对应的
    // pipeline_definition 是跨 worker 的复合 pipeline（含 EXPORT_* + DISPATCH_* 各类 impl_code），
    // EXPORT worker 加载后会在 DISPATCH_PREPARE 这种异域 step 上报 STEP_NOT_FOUND。
    // 注：task_payload 在 executionContext 里是 JSON 字符串（key=payload），不是 flatten 的字段；
    // 所以这里要先解出字符串再取 targetJobCode。
    String targetJobCode = resolveText(attributes, "targetJobCode");
    if (!Texts.hasText(targetJobCode)) {
      targetJobCode = extractFromPayloadJson(attributes, "targetJobCode");
    }
    if (Texts.hasText(targetJobCode)) {
      return targetJobCode;
    }
    String jobCode =
        resolveText(
            attributes,
            PipelineRuntimeKeys.JOB_CODE,
            PipelineRuntimeKeys.PIPELINE_CODE,
            "jobCode",
            "pipelineCode");
    if (Texts.hasText(jobCode)) {
      return jobCode;
    }
    if (request != null && Texts.hasText(request.jobCode())) {
      return request.jobCode();
    }
    if (request != null && Texts.hasText(request.stepCode())) {
      return request.stepCode();
    }
    return pipelineType();
  }

  /**
   * 从 executionContext 里塞的原始 `payload` JSON 字符串中抽一个顶层字符串字段。 用于在未把 payload flatten 成 attributes
   * 的场景下读 `targetJobCode` / `templateCode` 等。
   */
  @SuppressWarnings("unchecked")
  private String extractFromPayloadJson(Map<String, Object> attributes, String fieldName) {
    Object raw = attributes == null ? null : attributes.get("payload");
    if (!(raw instanceof String payload) || payload.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(payload, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Object v = ((Map<String, Object>) map).get(fieldName);
        if (v instanceof String s && Texts.hasText(s)) {
          return s;
        }
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          AbstractPipelineStepExecutionAdapter.class, "catch:IllegalArgumentException", ignored);

      // 非 JSON / 非对象 payload 不阻断调度，回退到其他 jobCode 来源
    }
    return null;
  }

  /**
   * ADR-026: 从 attributes 顶层或 payload JSON 抽 dryRun。orchestrator 把 dryRun 塞 task
   * payload；少量旧调用方可能直接塞 attributes 顶层 — 都兼容。
   */
  @SuppressWarnings("unchecked")
  private boolean resolveDryRunFromPayload(Map<String, Object> attributes) {
    if (attributes == null) {
      return false;
    }
    Object direct = attributes.get(PipelineRuntimeKeys.DRY_RUN);
    if (direct instanceof Boolean b) {
      return b;
    }
    if (direct != null && "true".equalsIgnoreCase(String.valueOf(direct))) {
      return true;
    }
    Object raw = attributes.get("payload");
    if (!(raw instanceof String payload) || payload.isBlank()) {
      return false;
    }
    try {
      Object parsed = JsonUtils.fromJson(payload, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Object v = ((Map<String, Object>) map).get(PipelineRuntimeKeys.DRY_RUN);
        if (v instanceof Boolean b) {
          return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
      }
    } catch (IllegalArgumentException ignored) {
      SwallowedExceptionLogger.info(
          AbstractPipelineStepExecutionAdapter.class,
          "catch:dry_run_payload_parse_failure",
          ignored);
    }
    return false;
  }

  protected String resolveText(Map<String, Object> attributes, String... keys) {
    for (String key : keys) {
      Object value = attributes.get(key);
      if (value instanceof String text && Texts.hasText(text)) {
        return text;
      }
      if (value != null) {
        String text = String.valueOf(value);
        if (Texts.hasText(text) && !"null".equalsIgnoreCase(text)) {
          return text;
        }
      }
    }
    return null;
  }

  private String lastSuccessfulStage(Map<String, Object> attributes) {
    Object lastSuccessStage = attributes.get(PipelineRuntimeKeys.PIPELINE_LAST_SUCCESS_STAGE);
    return lastSuccessStage == null ? initialStage() : String.valueOf(lastSuccessStage);
  }

  private R firstFailure(List<R> results) {
    if (results == null) {
      return null;
    }
    return results.stream().filter(result -> !isSuccess(result)).findFirst().orElse(null);
  }

  protected abstract String pipelineType();

  protected abstract String pipelineDescription();

  protected abstract List<PipelineStepTemplate> defaultPipelineSteps();

  protected abstract String initialStage();

  protected abstract C buildContext(
      StepExecutionRequest request, Map<String, Object> attributes, Long fileId) throws Exception;

  protected abstract List<R> executeStages(C context);

  protected abstract boolean isSuccess(R result);

  protected abstract String resultStage(R result);

  protected abstract String resultCode(R result);

  protected abstract String resultMessage(R result);

  protected String resultErrorKey(R result) {
    return result instanceof StageExecutionResult stageResult ? stageResult.errorKey() : null;
  }

  protected String resultErrorArgs(R result) {
    return result instanceof StageExecutionResult stageResult ? stageResult.errorArgs() : null;
  }

  protected abstract StepExecutionResponse buildSuccessResponse(
      C context, List<R> results, Map<String, Object> attributes);

  /**
   * 4 个 {@code *StepExecutionAdapter#buildContext} 共有的"5 行 setter 模板" — tenantId / jobCode /
   * workerId / rawPayload / attributes。子类先 {@code new XxxJobContext()} 再调本方法填公共字段，最后按需补自己独有的字段 （如
   * ExportJobContext.fileId / DispatchJobContext.dispatchId / ProcessJobContext.batchKey）。
   *
   * <p>{@code bizDate} 不在此处填：4 个 context 里 ProcessJobContext 没有该字段，子类自己设。
   */
  protected void populateCommonFields(
      C context, StepExecutionRequest request, Map<String, Object> attributes) {
    context.setTenantId(request.tenantId());
    context.setJobCode(String.valueOf(attributes.getOrDefault("jobCode", request.jobCode())));
    context.setWorkerId(request.workerId());
    context.setRawPayload(String.valueOf(attributes.getOrDefault("payload", "")));
    context.setAttributes(attributes);
  }

  /**
   * 4 个 {@code *StepExecutionAdapter#buildSuccessResponse} 里同字节复制的 NODE_OUTPUTS put helper： 空值 /
   * 空白字符串视为缺省，不写入。
   */
  protected static void putIfPresent(Map<String, Object> target, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String text && text.isBlank()) {
      return;
    }
    target.put(key, value);
  }

  /**
   * pipeline 失败后的补偿钩子。默认实现：当 attributes 中存在 {@code fileId} 时，把 file_record 状态推进为 FAILED
   * 并把错误三元组（code / message / errorKey / errorArgs）写进 metadata；不绑定 file_record 的链路（如 PROCESS）应覆盖为
   * no-op。
   */
  protected void handlePipelineFailure(
      Map<String, Object> attributes,
      String errorCode,
      String errorMessage,
      String errorKey,
      String errorArgs) {
    Long fileId = runtimeRepository().toLong(attributes.get(PipelineRuntimeKeys.FILE_ID));
    if (fileId == null) {
      return;
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("errorCode", errorCode);
    metadata.put("errorMessage", errorMessage);
    metadata.put("errorKey", errorKey);
    metadata.put("errorArgs", errorArgs);
    runtimeRepository().updateFileStatus(fileId, "FAILED", metadata);
  }
}
