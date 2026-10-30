package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleResourceQueueApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueCreateRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueListRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueUpdateRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ResourceQueueResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.domain.ops.view.ResourceQueueRow;
import io.github.pinpols.batch.console.domain.param.ResourceQueueInsertParam;
import io.github.pinpols.batch.console.domain.param.ResourceQueueUpdateParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleResourceQueueApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleResourceQueueApplicationService
    implements ConsoleResourceQueueApplicationService {

  private final ResourceQueueMapper resourceQueueMapper;
  private final ConsoleTenantGuard tenantGuard;

  @Override
  public PageResponse<ResourceQueueResponse> list(ResourceQueueListRequest request) {
    String resolved = tenantGuard.resolveTenant(request.tenantId());
    String normalizedQueueCode =
        CodeNormalizer.normalizeConfigCode(request.queueCode(), "queueCode");
    PageRequest pageRequest = new PageRequest(request.pageNo(), request.pageSize());
    ResourceQueueQuery query = new ResourceQueueQuery(
        resolved, normalizedQueueCode, request.queueType(), request.enabled(), pageRequest);
    long total = resourceQueueMapper.countByQuery(query);
    List<ResourceQueueResponse> items = resourceQueueMapper.selectByQuery(query).stream()
        .map(ResourceQueueResponse::from)
        .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public ResourceQueueResponse create(ResourceQueueCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    String queueCode = CodeNormalizer.normalizeConfigCode(request.getQueueCode(), "queueCode");
    ResourceQueueRow existing = resourceQueueMapper.selectByUniqueKey(tenantId, queueCode);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "queue code already exists: " + request.getQueueCode());
    }
    ResourceQueueInsertParam params = ResourceQueueInsertParam.builder()
        .tenantId(tenantId)
        .queueCode(queueCode)
        .queueName(request.getQueueName())
        .queueType(request.getQueueType())
        .maxRunningJobs(
            EmptyChecks.isNotNull(request.getMaxRunningJobs()) ? request.getMaxRunningJobs() : 0)
        .maxRunningPartitions(
            EmptyChecks.isNotNull(request.getMaxRunningPartitions())
                ? request.getMaxRunningPartitions()
                : 0)
        .maxQps(EmptyChecks.isNotNull(request.getMaxQps()) ? request.getMaxQps() : 0)
        .workerGroup(request.getWorkerGroup())
        .resourceTag(request.getResourceTag())
        .priorityPolicy(
            EmptyChecks.isNotNull(request.getPriorityPolicy())
                ? request.getPriorityPolicy()
                : "FIFO")
        .fairShareWeight(
            EmptyChecks.isNotNull(request.getFairShareWeight()) ? request.getFairShareWeight() : 1)
        .enabled(EmptyChecks.isNull(request.getEnabled()) || request.getEnabled())
        .description(request.getDescription())
        .build();
    resourceQueueMapper.insert(params);
    long id = params.getId();
    ResourceQueueRow createdQueue = resourceQueueMapper.selectById(tenantId, id);
    return ResourceQueueResponse.from(createdQueue);
  }

  @Override
  public ResourceQueueResponse update(Long id, ResourceQueueUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    ResourceQueueRow selectedQueue = resourceQueueMapper.selectById(tenantId, id);
    ResourceQueueRow existing = Guard.requireFound(selectedQueue, "resource queue not found");
    ResourceQueueUpdateParam params = ResourceQueueUpdateParam.builder()
        .tenantId(tenantId)
        .id(id)
        .queueName(
            EmptyChecks.isNotNull(request.getQueueName())
                ? request.getQueueName()
                : existing.queueName())
        .queueType(
            EmptyChecks.isNotNull(request.getQueueType())
                ? request.getQueueType()
                : existing.queueType())
        .maxRunningJobs(
            EmptyChecks.isNotNull(request.getMaxRunningJobs())
                ? request.getMaxRunningJobs()
                : existing.maxRunningJobs())
        .maxRunningPartitions(
            EmptyChecks.isNotNull(request.getMaxRunningPartitions())
                ? request.getMaxRunningPartitions()
                : existing.maxRunningPartitions())
        .maxQps(
            EmptyChecks.isNotNull(request.getMaxQps()) ? request.getMaxQps() : existing.maxQps())
        .workerGroup(
            EmptyChecks.isNotNull(request.getWorkerGroup())
                ? request.getWorkerGroup()
                : existing.workerGroup())
        .resourceTag(
            EmptyChecks.isNotNull(request.getResourceTag())
                ? request.getResourceTag()
                : existing.resourceTag())
        .priorityPolicy(
            EmptyChecks.isNotNull(request.getPriorityPolicy())
                ? request.getPriorityPolicy()
                : existing.priorityPolicy())
        .fairShareWeight(
            EmptyChecks.isNotNull(request.getFairShareWeight())
                ? request.getFairShareWeight()
                : existing.fairShareWeight())
        .enabled(
            EmptyChecks.isNotNull(request.getEnabled()) ? request.getEnabled() : existing.enabled())
        .description(
            EmptyChecks.isNotNull(request.getDescription())
                ? request.getDescription()
                : existing.description())
        .build();
    resourceQueueMapper.update(params);
    ResourceQueueRow updatedQueue = resourceQueueMapper.selectById(tenantId, id);
    return ResourceQueueResponse.from(updatedQueue);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = resourceQueueMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.resource_queue.not_found");
    }
  }
}
