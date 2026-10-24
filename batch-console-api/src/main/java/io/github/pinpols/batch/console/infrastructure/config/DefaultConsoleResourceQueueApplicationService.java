package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.ops.query.ResourceQueueQuery;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueListRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.config.ConsoleResourceQueueApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueCreateRequest;
import io.github.pinpols.batch.console.application.contract.request.config.ResourceQueueUpdateRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ResourceQueueResponse;
import io.github.pinpols.batch.console.domain.ops.mapper.ResourceQueueMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    String normalizedQueueCode = CodeNormalizer.normalizeConfigCode(request.queueCode(), "queueCode");
    PageRequest pageRequest = new PageRequest(request.pageNo(), request.pageSize());
    ResourceQueueQuery query = new ResourceQueueQuery(
        resolved, normalizedQueueCode, request.queueType(), request.enabled(), pageRequest);
    long total =
        resourceQueueMapper.countByQuery(query);
    List<ResourceQueueResponse> items =
        resourceQueueMapper
            .selectByQuery(query)
            .stream()
            .map(ResourceQueueResponse::from)
            .toList();
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public ResourceQueueResponse create(ResourceQueueCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    String queueCode = CodeNormalizer.normalizeConfigCode(request.getQueueCode(), "queueCode");
    Map<String, Object> existing = resourceQueueMapper.selectByUniqueKey(tenantId, queueCode);
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "queue code already exists: " + request.getQueueCode());
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("queue_code", queueCode);
    params.put("queue_name", request.getQueueName());
    params.put("queue_type", request.getQueueType());
    params.put(
        "max_running_jobs", request.getMaxRunningJobs() != null ? request.getMaxRunningJobs() : 0);
    params.put(
        "max_running_partitions",
        request.getMaxRunningPartitions() != null ? request.getMaxRunningPartitions() : 0);
    params.put("max_qps", request.getMaxQps() != null ? request.getMaxQps() : 0);
    params.put("worker_group", request.getWorkerGroup());
    params.put("resource_tag", request.getResourceTag());
    params.put(
        "priority_policy",
        request.getPriorityPolicy() != null ? request.getPriorityPolicy() : "FIFO");
    params.put(
        "fair_share_weight",
        request.getFairShareWeight() != null ? request.getFairShareWeight() : 1);
    params.put("enabled", request.getEnabled() == null || request.getEnabled());
    params.put("description", request.getDescription());
    resourceQueueMapper.insert(params);
    Long id = ((Number) params.get("id")).longValue();
    Map<String, Object> createdQueue = resourceQueueMapper.selectById(tenantId, id);
    return ResourceQueueResponse.from(createdQueue);
  }

  @Override
  public ResourceQueueResponse update(Long id, ResourceQueueUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> selectedQueue = resourceQueueMapper.selectById(tenantId, id);
    Map<String, Object> existing = Guard.requireFound(selectedQueue, "resource queue not found");
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("id", id);
    params.put(
        "queue_name",
        request.getQueueName() != null ? request.getQueueName() : existing.get("queue_name"));
    params.put(
        "queue_type",
        request.getQueueType() != null ? request.getQueueType() : existing.get("queue_type"));
    params.put(
        "max_running_jobs",
        request.getMaxRunningJobs() != null
            ? request.getMaxRunningJobs()
            : existing.get("max_running_jobs"));
    params.put(
        "max_running_partitions",
        request.getMaxRunningPartitions() != null
            ? request.getMaxRunningPartitions()
            : existing.get("max_running_partitions"));
    params.put(
        "max_qps", request.getMaxQps() != null ? request.getMaxQps() : existing.get("max_qps"));
    params.put(
        "worker_group",
        request.getWorkerGroup() != null ? request.getWorkerGroup() : existing.get("worker_group"));
    params.put(
        "resource_tag",
        request.getResourceTag() != null ? request.getResourceTag() : existing.get("resource_tag"));
    params.put(
        "priority_policy",
        request.getPriorityPolicy() != null
            ? request.getPriorityPolicy()
            : existing.get("priority_policy"));
    params.put(
        "fair_share_weight",
        request.getFairShareWeight() != null
            ? request.getFairShareWeight()
            : existing.get("fair_share_weight"));
    params.put(
        "enabled", request.getEnabled() != null ? request.getEnabled() : existing.get("enabled"));
    params.put(
        "description",
        request.getDescription() != null ? request.getDescription() : existing.get("description"));
    resourceQueueMapper.update(params);
    Map<String, Object> updatedQueue = resourceQueueMapper.selectById(tenantId, id);
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
