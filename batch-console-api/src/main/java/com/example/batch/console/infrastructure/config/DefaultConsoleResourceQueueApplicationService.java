package com.example.batch.console.infrastructure.config;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.config.ConsoleResourceQueueApplicationService;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.config.ResourceQueueCreateRequest;
import com.example.batch.console.web.request.config.ResourceQueueUpdateRequest;
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
  public PageResponse<Map<String, Object>> list(
      String tenantId,
      String queueCode,
      String queueType,
      Boolean enabled,
      int pageNo,
      int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = resourceQueueMapper.countByQuery(resolved, queueCode, queueType, enabled);
    List<Map<String, Object>> items =
        resourceQueueMapper.selectByQuery(resolved, queueCode, queueType, enabled, pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> create(ResourceQueueCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        resourceQueueMapper.selectByUniqueKey(tenantId, request.getQueueCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "queue code already exists: " + request.getQueueCode());
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("queue_code", request.getQueueCode());
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
    params.put("enabled", request.getEnabled() != null ? request.getEnabled() : true);
    params.put("description", request.getDescription());
    resourceQueueMapper.insert(params);
    Long id = ((Number) params.get("id")).longValue();
    return resourceQueueMapper.selectById(tenantId, id);
  }

  @Override
  public Map<String, Object> update(Long id, ResourceQueueUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(
            resourceQueueMapper.selectById(tenantId, id), "resource queue not found");
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
    return resourceQueueMapper.selectById(tenantId, id);
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
