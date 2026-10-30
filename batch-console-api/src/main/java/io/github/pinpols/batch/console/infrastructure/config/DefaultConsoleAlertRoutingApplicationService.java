package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.application.contract.request.config.AlertRoutingSaveRequest;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleAlertRoutingApplicationService;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertRoutingQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertRoutingResponse;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigCountQuery;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;
import io.github.pinpols.batch.console.domain.notification.view.AlertRoutingConfigRow;
import io.github.pinpols.batch.console.domain.param.AlertRoutingConfigUpsertParam;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
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
  public PageResponse<ConsoleAlertRoutingResponse> list(AlertRoutingQueryRequest request) {
    String resolved = tenantGuard.resolveTenant(request.getTenantId());
    PageRequest pageRequest = new PageRequest(request.getPageNo(), request.getPageSize());
    AlertRoutingConfigCountQuery alertRoutingConfigCountQuery =
        AlertRoutingConfigCountQuery.builder()
            .tenantId(resolved)
            .keyword(request.getKeyword())
            .routeCode(request.getRouteCode())
            .team(request.getTeam())
            .severity(request.getSeverity())
            .enabled(request.getEnabled())
            .build();
    long total = alertRoutingConfigMapper.countByQuery(alertRoutingConfigCountQuery);
    AlertRoutingConfigQuery alertRoutingConfigQuery = AlertRoutingConfigQuery.builder()
        .tenantId(resolved)
        .keyword(request.getKeyword())
        .routeCode(request.getRouteCode())
        .team(request.getTeam())
        .severity(request.getSeverity())
        .enabled(request.getEnabled())
        .pageRequest(pageRequest)
        .build();
    List<AlertRoutingConfigRow> items =
        alertRoutingConfigMapper.selectByQuery(alertRoutingConfigQuery);
    long responseTotal = total;
    int responsePageNo = pageRequest.pageNo();
    int responsePageSize = pageRequest.pageSize();
    List<ConsoleAlertRoutingResponse> responses =
        items.stream().map(ConsoleAlertRoutingResponse::from).toList();
    return new PageResponse<>(responseTotal, responsePageNo, responsePageSize, responses);
  }

  @Override
  public ConsoleAlertRoutingResponse create(AlertRoutingSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    AlertRoutingConfigRow existing =
        alertRoutingConfigMapper.selectByUniqueKey(tenantId, request.getRouteCode());
    if (existing != null) {
      throw BizException.of(
          ResultCode.CONFLICT,
          ResultCode.CONFLICT.detailKey(),
          "route code already exists: " + request.getRouteCode());
    }
    AlertRoutingConfigUpsertParam param = toParam(null, tenantId, request);
    alertRoutingConfigMapper.upsertAlertRoutingConfig(param);
    AlertRoutingConfigRow createdRoute =
        alertRoutingConfigMapper.selectByUniqueKey(tenantId, param.getRouteCode());
    return ConsoleAlertRoutingResponse.from(createdRoute);
  }

  @Override
  public ConsoleAlertRoutingResponse update(Long id, AlertRoutingSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    AlertRoutingConfigRow selectedRoute = alertRoutingConfigMapper.selectById(tenantId, id);
    AlertRoutingConfigRow existing = Guard.requireFound(selectedRoute, "alert routing not found");
    String existingRouteCode = existing.routeCode();
    if (!existingRouteCode.equals(request.getRouteCode())) {
      AlertRoutingConfigRow duplicate =
          alertRoutingConfigMapper.selectByUniqueKey(tenantId, request.getRouteCode());
      if (duplicate != null) {
        throw BizException.of(
            ResultCode.CONFLICT,
            ResultCode.CONFLICT.detailKey(),
            "route code already exists: " + request.getRouteCode());
      }
    }
    AlertRoutingConfigUpsertParam param = toParam(id, tenantId, request);
    // PATCH 语义:request 没传的字段保留 existing 值,避免 NOT NULL 违反 (BE-ISSUE-6)
    mergeWithExisting(param, request, existing);
    alertRoutingConfigMapper.updateById(param);
    AlertRoutingConfigRow updatedRoute = alertRoutingConfigMapper.selectById(tenantId, id);
    return ConsoleAlertRoutingResponse.from(updatedRoute);
  }

  private static void mergeWithExisting(
      AlertRoutingConfigUpsertParam param,
      AlertRoutingSaveRequest request,
      AlertRoutingConfigRow existing) {
    if (request.getRouteName() == null) {
      param.setRouteName(existing.routeName());
    }
    if (request.getTeam() == null) {
      param.setTeam(existing.team());
    }
    if (request.getAlertGroup() == null) {
      param.setAlertGroup(existing.alertGroup());
    }
    if (request.getSeverity() == null) {
      param.setSeverity(existing.severity());
    }
    if (request.getReceiver() == null) {
      param.setReceiver(existing.receiver());
    }
    if (request.getGroupBy() == null) {
      param.setGroupBy(existing.groupBy());
    }
    if (request.getDescription() == null) {
      param.setDescription(existing.description());
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
    Integer groupWaitSeconds = defaultInt(request.getGroupWaitSeconds(), 0);
    param.setGroupWaitSeconds(groupWaitSeconds);
    Integer groupIntervalSeconds = defaultInt(request.getGroupIntervalSeconds(), 300);
    param.setGroupIntervalSeconds(groupIntervalSeconds);
    Integer repeatIntervalSeconds = defaultInt(request.getRepeatIntervalSeconds(), 3600);
    param.setRepeatIntervalSeconds(repeatIntervalSeconds);
    param.setEnabled(request.getEnabled() == null || request.getEnabled());
    param.setDescription(request.getDescription());
    param.setCreatedBy(operator);
    param.setUpdatedBy(operator);
    return param;
  }

  private static Integer defaultInt(Integer value, int fallback) {
    return value != null ? value : fallback;
  }
}
