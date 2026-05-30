package com.example.batch.console.domain.job.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.domain.job.application.ConsoleBatchWindowApplicationService;
import com.example.batch.console.domain.job.mapper.BatchWindowMapper;
import com.example.batch.console.domain.job.web.request.BatchWindowCreateRequest;
import com.example.batch.console.domain.job.web.request.BatchWindowUpdateRequest;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
import com.example.batch.console.infrastructure.config.ConsoleConfigCacheInvalidationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleBatchWindowApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleBatchWindowApplicationService
    implements ConsoleBatchWindowApplicationService {

  private final BatchWindowMapper batchWindowMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Override
  public PageResponse<Map<String, Object>> list(
      String tenantId, String windowCode, Boolean enabled, int pageNo, int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = batchWindowMapper.countByQuery(resolved, windowCode, enabled);
    List<Map<String, Object>> items =
        batchWindowMapper.selectByQuery(resolved, windowCode, enabled, pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> create(BatchWindowCreateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        batchWindowMapper.selectByUniqueKey(tenantId, request.getWindowCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "window code already exists: " + request.getWindowCode());
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("window_code", request.getWindowCode());
    params.put("window_name", request.getWindowName());
    params.put("timezone", request.getTimezone());
    params.put("start_time", request.getStartTime());
    params.put("end_time", request.getEndTime());
    params.put(
        "end_strategy",
        request.getEndStrategy() != null ? request.getEndStrategy() : "FINISH_RUNNING");
    params.put(
        "out_of_window_action",
        request.getOutOfWindowAction() != null ? request.getOutOfWindowAction() : "WAIT");
    params.put(
        "allow_cross_day", request.getAllowCrossDay() != null ? request.getAllowCrossDay() : false);
    params.put("enabled", request.getEnabled() != null ? request.getEnabled() : true);
    params.put("description", request.getDescription());
    batchWindowMapper.insert(params);
    cacheInvalidationService.evictBatchWindow(tenantId, request.getWindowCode());
    Long id = ((Number) params.get("id")).longValue();
    return batchWindowMapper.selectById(tenantId, id);
  }

  @Override
  public Map<String, Object> update(Long id, BatchWindowUpdateRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(batchWindowMapper.selectById(tenantId, id), "batch window not found");
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("id", id);
    params.put(
        "window_name",
        request.getWindowName() != null ? request.getWindowName() : existing.get("window_name"));
    params.put(
        "timezone",
        request.getTimezone() != null ? request.getTimezone() : existing.get("timezone"));
    params.put(
        "start_time",
        request.getStartTime() != null
            ? request.getStartTime()
            : String.valueOf(existing.get("start_time")));
    params.put(
        "end_time",
        request.getEndTime() != null
            ? request.getEndTime()
            : String.valueOf(existing.get("end_time")));
    params.put(
        "end_strategy",
        request.getEndStrategy() != null ? request.getEndStrategy() : existing.get("end_strategy"));
    params.put(
        "out_of_window_action",
        request.getOutOfWindowAction() != null
            ? request.getOutOfWindowAction()
            : existing.get("out_of_window_action"));
    params.put(
        "allow_cross_day",
        request.getAllowCrossDay() != null
            ? request.getAllowCrossDay()
            : existing.get("allow_cross_day"));
    params.put(
        "enabled", request.getEnabled() != null ? request.getEnabled() : existing.get("enabled"));
    params.put(
        "description",
        request.getDescription() != null ? request.getDescription() : existing.get("description"));
    batchWindowMapper.update(params);
    cacheInvalidationService.evictBatchWindow(
        tenantId, String.valueOf(existing.get("window_code")));
    return batchWindowMapper.selectById(tenantId, id);
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = batchWindowMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_window.not_found");
    }
    Map<String, Object> window = batchWindowMapper.selectById(resolved, id);
    if (window != null) {
      cacheInvalidationService.evictBatchWindow(
          resolved, String.valueOf(window.get("window_code")));
    }
  }
}
