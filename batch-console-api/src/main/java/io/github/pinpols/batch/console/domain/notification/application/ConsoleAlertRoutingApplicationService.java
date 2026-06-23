package io.github.pinpols.batch.console.domain.notification.application;

import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.console.web.request.config.AlertRoutingSaveRequest;
import java.util.Map;

/** 告警路由应用服务：管理租户告警路由的 CRUD 及启停操作。 */
public interface ConsoleAlertRoutingApplicationService {

  PageResponse<Map<String, Object>> list(
      String tenantId,
      String routeCode,
      String team,
      String severity,
      Boolean enabled,
      int pageNo,
      int pageSize);

  Map<String, Object> create(AlertRoutingSaveRequest request);

  Map<String, Object> update(Long id, AlertRoutingSaveRequest request);

  void toggle(Long id, String tenantId, Boolean enabled);
}
