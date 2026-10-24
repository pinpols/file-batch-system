package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigCountQuery;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertRoutingQueryRequest;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("告警路由分页服务: 筛选条件透传与分页响应")
class DefaultConsoleAlertRoutingApplicationServiceTest {

  private final AlertRoutingConfigMapper mapper = mock(AlertRoutingConfigMapper.class);
  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver metadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final DefaultConsoleAlertRoutingApplicationService service =
      new DefaultConsoleAlertRoutingApplicationService(mapper, tenantGuard, metadataResolver);

  @Test
  @DisplayName("按查询条件计数并返回请求页的数据")
  void list_uses_query_filters_and_returns_paged_rows() {
    AlertRoutingQueryRequest request = new AlertRoutingQueryRequest();
    request.setTenantId("ta");
    request.setKeyword("payment");
    request.setRouteCode("PAYMENT");
    request.setTeam("ops");
    request.setSeverity("WARN");
    request.setEnabled(true);
    request.setPageNo(3);
    request.setPageSize(15);
    when(tenantGuard.resolveTenant("ta")).thenReturn("ta");
    when(mapper.countByQuery(AlertRoutingConfigCountQuery.builder()
        .tenantId("ta")
        .keyword("payment")
        .routeCode("PAYMENT")
        .team("ops")
        .severity("WARN")
        .enabled(true)
        .build())).thenReturn(31L);
    when(mapper.selectByQuery(
            AlertRoutingConfigQuery.builder()
                .tenantId("ta")
                .keyword("payment")
                .routeCode("PAYMENT")
                .team("ops")
                .severity("WARN")
                .enabled(true)
                .pageRequest(new PageRequest(3, 15))
                .build()))
        .thenReturn(List.of(Map.of("id", 7L, "route_code", "PAYMENT")));

    var response = service.list(request);

    assertEquals(31L, response.total());
    assertEquals(3, response.pageNo());
    assertEquals(15, response.pageSize());
    assertEquals(7L, response.items().getFirst().id());
    verify(mapper)
        .selectByQuery(AlertRoutingConfigQuery.builder()
            .tenantId("ta")
            .keyword("payment")
            .routeCode("PAYMENT")
            .team("ops")
            .severity("WARN")
            .enabled(true)
            .pageRequest(new PageRequest(3, 15))
            .build());
  }
}
