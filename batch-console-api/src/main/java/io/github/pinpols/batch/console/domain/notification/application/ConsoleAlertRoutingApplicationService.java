package io.github.pinpols.batch.console.domain.notification.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.application.contract.request.config.AlertRoutingSaveRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertRoutingQueryRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertRoutingResponse;

/** 告警路由应用服务：管理租户告警路由的 CRUD 及启停操作。 */
public interface ConsoleAlertRoutingApplicationService {

  PageResponse<ConsoleAlertRoutingResponse> list(AlertRoutingQueryRequest request);

  ConsoleAlertRoutingResponse create(AlertRoutingSaveRequest request);

  ConsoleAlertRoutingResponse update(Long id, AlertRoutingSaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
