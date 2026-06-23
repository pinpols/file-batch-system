package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.SdkProtocolVersions;
import io.github.pinpols.batch.common.dto.SdkVersions;
import io.github.pinpols.batch.common.dto.WorkerHeartbeatDto;
import io.github.pinpols.batch.common.dto.WorkerTaskTypeDescriptorDto;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.WorkerRegistryStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.SensitiveDataValidator;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkerRegistryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.CustomTaskTypeUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.param.TouchHeartbeatParam;
import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import io.github.pinpols.batch.orchestrator.infrastructure.progress.PipelineStageProgressCache;
import io.github.pinpols.batch.orchestrator.mapper.CustomTaskTypeRegistryMapper;
import io.github.pinpols.batch.orchestrator.mapper.SystemParameterMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkerRegistryMapper;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 注册表服务端入口：register（新建/重连）/ heartbeat（续活）/ updateStatus / deactivate。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>DRAINING / DECOMMISSIONED 状态不可被心跳重置为 ONLINE</b>——见 {@link #resolveHeartbeatStatus}。 否则
 *       {@link
 *       io.github.pinpols.batch.orchestrator.application.service.DefaultWorkerDrainGovernanceService}
 *       正在执行的 drain / decommission 会被 worker 端的周期心跳悄悄回滚。
 *   <li><b>heartbeat 未注册时自动降级到 register</b>——回退首次 register 请求丢失的竞态，worker 不会因为 register 漏发就永远心跳无主。
 *   <li><b>幂等 upsert</b>：register 对已存在记录走 {@code withHeartbeat} 更新而不是报错，允许 worker 重启后 重新 register
 *       同一 workerCode。
 * </ul>
 */
@Slf4j
@Service("orchestratorWorkerRegistryService")
@RequiredArgsConstructor
public class DefaultWorkerRegistryService implements WorkerRegistryServerService {

  /** 租户级最低 SDK 主版本门禁的 system_parameter 键。console 在该键写最低版本字符串(如 {@code "1.0.0"} / {@code "2"})。 */
  private static final String MIN_SDK_VERSION_PARAM_KEY = "worker.min_sdk_version";

  private final WorkerRegistryMapper workerRegistryMapper;
  private final CustomTaskTypeRegistryMapper customTaskTypeRegistryMapper;
  private final PipelineStageProgressCache pipelineStageProgressCache;
  private final SystemParameterMapper systemParameterMapper;

  @Lazy @Autowired private DefaultWorkerRegistryService self;

  @Override
  @Transactional
  public WorkerRegistryEntity register(WorkerHeartbeatDto request) {
    rejectUnsupportedProtocolVersion(request);
    rejectOutdatedSdkVersion(request);
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), request.workerCode());
    String newStatus =
        resolveIncomingStatus(
            request,
            WorkerRegistryStatus.ONLINE.code(),
            registry == null ? null : registry.status());
    Instant heartbeatAt = firstHeartbeat();
    Integer newLoad =
        request.currentLoad() != null
            ? request.currentLoad()
            : (registry == null ? 0 : registry.currentLoad());
    JsonbString newTags =
        request.capabilityTags() != null
            ? JsonbString.of(JsonUtils.toJson(request.capabilityTags()))
            : (registry == null ? null : registry.capabilityTags());

    if (registry == null) {
      registry =
          new WorkerRegistryEntity(
              null,
              request.tenantId(),
              request.workerCode(),
              request.workerGroup(),
              newTags,
              null,
              newStatus,
              heartbeatAt,
              newLoad,
              null, // maxConcurrent: 走 DB DEFAULT 10 (V87)
              null,
              null,
              request.hostName(),
              request.hostIp(),
              request.processId(),
              request.buildId(),
              request.sdkVersion());
    } else {
      // SDK-P5-3:register 刷新运行指纹(worker 重启可能换 host / 升 SDK 版本);request 未带的字段 mapper 端 coalesce
      // 保留旧值。
      registry =
          registry
              .withHeartbeat(newStatus, heartbeatAt, newLoad, newTags)
              .withFingerprint(
                  request.hostName(),
                  request.hostIp(),
                  request.processId(),
                  request.buildId(),
                  request.sdkVersion());
    }
    WorkerRegistryEntity saved = persist(registry);
    // ADR-035 §2:SDK 自托管 worker 通过 workerGroup="sdk-self-hosted" 识别,标到列上让
    // console "我的 Worker" 页过滤。幂等。
    if ("sdk-self-hosted".equals(request.workerGroup())) {
      workerRegistryMapper.markSelfHosted(request.tenantId(), request.workerCode());
    }
    upsertDeclaredTaskTypes(request);
    return saved;
  }

  /**
   * SDK 协议门禁:worker register 携带的 {@code protocolVersion} 主版本若不在平台支持集合内,直接拒绝注册(400)。
   *
   * <p>向后兼容:{@code protocolVersion} 为空(老 SDK / 非 SDK 平台 worker)按 legacy
   * 放行;present-but-unsupported(如 {@code "v3"})才拒。支持集合权威源 {@link
   * SdkProtocolVersions#SUPPORTED_MAJOR_VERSIONS}(镜像 sdk-shared-constants.yaml),与 console-api
   * 兼容告警({@code WorkerCompatibilityEvaluator})同源,互补:此处是注册准入硬门禁, 后者是运行期只读可见性提示。
   */
  private void rejectUnsupportedProtocolVersion(WorkerHeartbeatDto request) {
    String protocolVersion = request.protocolVersion();
    if (protocolVersion == null || protocolVersion.isBlank()) {
      return;
    }
    if (!SdkProtocolVersions.isSupportedMajor(protocolVersion)) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR,
          "error.worker.unsupported_protocol_version",
          protocolVersion,
          SdkProtocolVersions.SUPPORTED_MAJOR_VERSIONS);
    }
  }

  /**
   * 租户级最低 SDK 版本门禁(opt-in):worker register 自报的 {@code sdkVersion} 主版本若 <b>低于</b> 该租户在 {@code
   * batch.system_parameter}(key={@value #MIN_SDK_VERSION_PARAM_KEY})配置的最低主版本,拒绝注册(400 + i18n 引导升级)。
   *
   * <p>默认放行(向后兼容):
   *
   * <ul>
   *   <li>该租户未配该 key → 不门禁(opt-in,不破坏现有 worker)。
   *   <li>{@code sdkVersion} 空 / 空白 → 放行(legacy / 非 SDK worker,对齐协议门禁的 legacy 放行)。
   *   <li>配置值或上报值解析不出主版本(脏值)→ 放行 + log warn(不因脏配置 / 脏上报误杀注册)。
   * </ul>
   *
   * <p>只比主版本(对齐协议门禁与 console {@code WorkerCompatibilityEvaluator},均为主版本),解析复用 {@link
   * SdkVersions#parseMajor}。
   */
  private void rejectOutdatedSdkVersion(WorkerHeartbeatDto request) {
    String reportedSdkVersion = request.sdkVersion();
    if (reportedSdkVersion == null || reportedSdkVersion.isBlank()) {
      return;
    }
    String requiredMinVersion =
        systemParameterMapper.selectParamValue(request.tenantId(), MIN_SDK_VERSION_PARAM_KEY);
    if (requiredMinVersion == null || requiredMinVersion.isBlank()) {
      return;
    }
    Integer requiredMajor = SdkVersions.parseMajor(requiredMinVersion);
    if (requiredMajor == null) {
      log.warn(
          "tenant {} 配置的 {}=\"{}\" 解析不出主版本,跳过最低 SDK 版本门禁",
          request.tenantId(),
          MIN_SDK_VERSION_PARAM_KEY,
          requiredMinVersion);
      return;
    }
    Integer reportedMajor = SdkVersions.parseMajor(reportedSdkVersion);
    if (reportedMajor == null) {
      log.warn(
          "worker {} 上报的 sdkVersion=\"{}\" 解析不出主版本,跳过最低 SDK 版本门禁",
          request.workerCode(),
          reportedSdkVersion);
      return;
    }
    if (reportedMajor < requiredMajor) {
      throw BizException.of(
          ResultCode.VALIDATION_ERROR,
          "error.worker.sdk_version_too_old",
          reportedSdkVersion,
          requiredMinVersion);
    }
  }

  /**
   * SDK Phase 3 M3.1:把 register 上报的 {@code taskTypes[].descriptor} upsert 到 {@code
   * custom_task_type_registry}(source=SDK_DECLARED)。heartbeat 不带 taskTypes(null),仅 register 刷新。
   * descriptor 全文序列化为 JSON 存 JSONB;code 以 SDK 端 {@code SdkTaskHandler.taskType()} 为权威(SDK 装配已对齐)。
   */
  private void upsertDeclaredTaskTypes(WorkerHeartbeatDto request) {
    List<WorkerTaskTypeDescriptorDto> taskTypes = request.taskTypes();
    if (taskTypes == null || taskTypes.isEmpty()) {
      return;
    }
    for (WorkerTaskTypeDescriptorDto descriptor : taskTypes) {
      if (descriptor == null || descriptor.code() == null || descriptor.code().isBlank()) {
        continue;
      }
      // Lane C:在持久化前对 descriptor.defaults / inputSchema 做凭据静态拒入,
      // 拒了就让整次 register 抛 BizException(400),阻止凭据落入 custom_task_type_registry JSONB。
      String ctxLabel = "sdk.taskType.descriptor[" + descriptor.code() + "]";
      SensitiveDataValidator.rejectIfContainsSensitiveKeys(descriptor.defaults(), ctxLabel);
      SensitiveDataValidator.rejectIfContainsSensitiveKeys(descriptor.inputSchema(), ctxLabel);
      customTaskTypeRegistryMapper.upsertDeclared(
          CustomTaskTypeUpsertParam.builder()
              .tenantId(request.tenantId())
              .taskTypeCode(descriptor.code())
              .displayName(descriptor.displayName())
              .descriptor(JsonUtils.toJson(descriptor))
              .descriptorVersion(descriptor.version())
              .declaredByWorkerCode(request.workerCode())
              .build());
    }
  }

  @Override
  @Transactional
  public WorkerRegistryEntity heartbeat(String workerCode, WorkerHeartbeatDto request) {
    if (request == null) {
      return null;
    }
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), workerCode);
    if (registry == null) {
      return self.register(request);
    }
    String newStatus = resolveHeartbeatStatus(request, registry.status());
    Integer newLoad =
        request.currentLoad() != null ? request.currentLoad() : registry.currentLoad();
    JsonbString newTags =
        request.capabilityTags() != null
            ? JsonbString.of(JsonUtils.toJson(request.capabilityTags()))
            : registry.capabilityTags();
    // heartbeat_at 由 mapper xml 直接写为 DB current_timestamp（消除 worker 时钟漂移）。
    workerRegistryMapper.touchHeartbeat(
        TouchHeartbeatParam.builder()
            .tenantId(request.tenantId())
            .workerCode(workerCode)
            .nextStatus(newStatus)
            .currentLoad(newLoad)
            .capabilityTags(newTags == null ? null : newTags.getValue())
            .build());
    // pipeline stage 行级进度(docs/design/pipeline-stage-progress-display.md):仅 LOAD/GENERATE
    // 在跑时非空,写 in-mem cache(不持久化,5min TTL,FE 经 Console 端点读)
    pipelineStageProgressCache.publish(
        request.tenantId(), workerCode, request.rowsProcessed(), request.totalRowsHint());
    return workerRegistryMapper.selectByTenantAndWorkerCode(request.tenantId(), workerCode);
  }

  @Override
  @Transactional
  public void deactivate(String tenantId, String workerCode) {
    self.updateStatus(tenantId, workerCode, WorkerRegistryStatus.OFFLINE.code());
  }

  @Override
  @Transactional
  public WorkerRegistryEntity updateStatus(String tenantId, String workerCode, String status) {
    WorkerRegistryEntity registry =
        workerRegistryMapper.selectByTenantAndWorkerCode(tenantId, workerCode);
    if (registry == null) {
      return null;
    }
    String newStatus = resolveIncomingStatus(null, status, registry.status());
    registry = registry.withStatus(newStatus, BatchDateTimeSupport.utcNow());
    return persist(registry);
  }

  /**
   * 首次注册 / register 路径的 heartbeat_at。Spring Data JDBC 路径必须 Java 端持有时间，无法直接用 SQL current_timestamp；
   * 这里统一用 orchestrator JVM 时钟，<b>忽略 worker 提供的 {@code request.heartbeatAt()}</b>，避免 worker NTP
   * 漂移直接进入 DB 的 heartbeat_at 列。心跳路径走 mybatis xml 直接 current_timestamp，二者口径一致。
   */
  private Instant firstHeartbeat() {
    return BatchDateTimeSupport.utcNow();
  }

  /**
   * MyBatis 替代原 Spring Data JDBC {@code repository.save}：id==null 走 insert（带 ON CONFLICT DO NOTHING
   * 防 UV 并发）；否则按 id 全字段 updateById。返回最新 DB 行（重新 selectByTenantAndWorkerCode 拿到带 id 的快照）。
   */
  private WorkerRegistryEntity persist(WorkerRegistryEntity registry) {
    if (registry.id() == null) {
      workerRegistryMapper.insert(registry);
    } else {
      workerRegistryMapper.updateById(registry);
    }
    return workerRegistryMapper.selectByTenantAndWorkerCode(
        registry.tenantId(), registry.workerCode());
  }

  private String resolveHeartbeatStatus(WorkerHeartbeatDto request, String currentStatus) {
    if (WorkerRegistryStatus.DECOMMISSIONED.code().equals(currentStatus)) {
      return currentStatus;
    }
    if (WorkerRegistryStatus.DRAINING.code().equals(currentStatus)) {
      return currentStatus;
    }
    return resolveIncomingStatus(request, WorkerRegistryStatus.ONLINE.code(), currentStatus);
  }

  private String resolveIncomingStatus(
      WorkerHeartbeatDto request, String defaultStatus, String currentStatus) {
    String requestedStatus = request == null ? null : request.status();
    // SDK 上报的是 worker 存活态(register/heartbeat 恒发 "RUNNING"),非注册表可用性枚举。
    // 仅当上报值是合法 WorkerRegistryStatus(ONLINE/OFFLINE/DRAINING/DECOMMISSIONED)才采纳,
    // 否则回落 defaultStatus(register/heartbeat 默认 ONLINE)/ currentStatus。
    // 防非枚举值(如 "RUNNING")直写违反 ck_worker_registry_status CHECK → 500
    // (契约 fixtures 01/03-06:请求 status=RUNNING → 存储/响应 ONLINE)。
    if (requestedStatus == null
        || requestedStatus.isBlank()
        || !isValidRegistryStatus(requestedStatus)) {
      return defaultStatus == null || defaultStatus.isBlank() ? currentStatus : defaultStatus;
    }
    return requestedStatus;
  }

  private static boolean isValidRegistryStatus(String code) {
    for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
      if (status.code().equals(code)) {
        return true;
      }
    }
    return false;
  }
}
