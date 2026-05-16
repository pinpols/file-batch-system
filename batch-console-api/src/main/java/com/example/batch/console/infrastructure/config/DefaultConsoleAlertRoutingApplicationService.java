package com.example.batch.console.infrastructure.config;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.config.ConsoleAlertRoutingApplicationService;
import com.example.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.config.AlertRoutingSaveRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link ConsoleAlertRoutingApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleAlertRoutingApplicationService
    implements ConsoleAlertRoutingApplicationService {

  private final AlertRoutingConfigMapper alertRoutingConfigMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @Override
  public PageResponse<Map<String, Object>> list(
      String tenantId,
      String routeCode,
      String team,
      String severity,
      Boolean enabled,
      int pageNo,
      int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total =
        alertRoutingConfigMapper.countByQuery(resolved, routeCode, team, severity, enabled);
    List<Map<String, Object>> items =
        alertRoutingConfigMapper.selectByQuery(
            resolved, routeCode, team, severity, enabled, pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public Map<String, Object> create(AlertRoutingSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        alertRoutingConfigMapper.selectByUniqueKey(tenantId, request.getRouteCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          "error.common.conflict_detail",
          "route code already exists: " + request.getRouteCode());
    }
    AlertRoutingConfigUpsertParam param = toParam(null, tenantId, request);
    alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
    return alertRoutingConfigMapper.selectByUniqueKey(tenantId, param.getRouteCode());
  }

  @Override
  public Map<String, Object> update(Long id, AlertRoutingSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(
            alertRoutingConfigMapper.selectById(tenantId, id), "alert routing not found");
    String existingRouteCode = String.valueOf(existing.get("route_code"));
    if (!existingRouteCode.equals(request.getRouteCode())) {
      Map<String, Object> duplicate =
          alertRoutingConfigMapper.selectByUniqueKey(tenantId, request.getRouteCode());
      if (duplicate != null) {
        throw BizException.of(
            ResultCode.CONFLICT,
            "error.common.conflict_detail",
            "route code already exists: " + request.getRouteCode());
      }
    }
    AlertRoutingConfigUpsertParam param = toParam(id, tenantId, request);
    // PATCH 语义:request 没传的字段保留 existing 值,避免 NOT NULL 违反 (BE-ISSUE-6)
    mergeWithExisting(param, request, existing);
    alertRoutingConfigMapper.updateById(param);
    return alertRoutingConfigMapper.selectById(tenantId, id);
  }

  private static void mergeWithExisting(
      AlertRoutingConfigUpsertParam param,
      AlertRoutingSaveRequest request,
      Map<String, Object> existing) {
    if (request.getRouteName() == null) {
      param.setRouteName((String) existing.get("route_name"));
    }
    if (request.getTeam() == null) {
      param.setTeam((String) existing.get("team"));
    }
    if (request.getAlertGroup() == null) {
      param.setAlertGroup((String) existing.get("alert_group"));
    }
    if (request.getSeverity() == null) {
      param.setSeverity((String) existing.get("severity"));
    }
    if (request.getReceiver() == null) {
      param.setReceiver((String) existing.get("receiver"));
    }
    if (request.getGroupBy() == null) {
      param.setGroupBy((String) existing.get("group_by"));
    }
    if (request.getDescription() == null) {
      param.setDescription((String) existing.get("description"));
    }
  }

  @Override
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = alertRoutingConfigMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.alert.routing_not_found");
    }
  }

  private AlertRoutingConfigUpsertParam toParam(
      Long id, String tenantId, AlertRoutingSaveRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    if (operator == null || operator.isBlank()) {
      operator = "system";
    }
    AlertRoutingConfigUpsertParam param = new AlertRoutingConfigUpsertParam();
    param.setId(id);
    param.setTenantId(tenantId);
    param.setRouteCode(request.getRouteCode());
    param.setRouteName(request.getRouteName());
    param.setTeam(request.getTeam());
    param.setAlertGroup(request.getAlertGroup());
    param.setSeverity(request.getSeverity());
    param.setReceiver(request.getReceiver());
    param.setGroupBy(request.getGroupBy());
    param.setGroupWaitSeconds(defaultInt(request.getGroupWaitSeconds(), 0));
    param.setGroupIntervalSeconds(defaultInt(request.getGroupIntervalSeconds(), 300));
    param.setRepeatIntervalSeconds(defaultInt(request.getRepeatIntervalSeconds(), 3600));
    param.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
    param.setDescription(request.getDescription());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    return param;
  }

  private static Integer defaultInt(Integer value, int fallback) {
    return value != null ? value : fallback;
  }
}
