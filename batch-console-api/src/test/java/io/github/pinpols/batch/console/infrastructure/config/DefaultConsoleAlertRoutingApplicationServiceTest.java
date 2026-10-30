package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.notification.application.contract.query.AlertRoutingQueryRequest;
import io.github.pinpols.batch.console.domain.notification.mapper.AlertRoutingConfigMapper;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigCountQuery;
import io.github.pinpols.batch.console.domain.notification.query.AlertRoutingConfigQuery;
import io.github.pinpols.batch.console.domain.notification.view.AlertRoutingConfigRow;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("告警路由分页服务: 筛选条件透传与分页响应")
@ExtendWith(MockitoExtension.class)
class DefaultConsoleAlertRoutingApplicationServiceTest {

  @Mock
  private AlertRoutingConfigMapper mapper;

  @Mock
  private ConsoleTenantGuard tenantGuard;

  @Mock
  private ConsoleRequestMetadataResolver metadataResolver;

  private DefaultConsoleAlertRoutingApplicationService service;

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
            .build()))
        .thenReturn(31L);
    when(mapper.selectByQuery(AlertRoutingConfigQuery.builder()
            .tenantId("ta")
            .keyword("payment")
            .routeCode("PAYMENT")
            .team("ops")
            .severity("WARN")
            .enabled(true)
            .pageRequest(new PageRequest(3, 15))
            .build()))
        .thenReturn(List.of(new AlertRoutingConfigRow(
            7L, "ta", "PAYMENT", null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null)));

    var response = service.list(request);

    assertThat(response.total()).isEqualTo(31L);
    assertThat(response.pageNo()).isEqualTo(3);
    assertThat(response.pageSize()).isEqualTo(15);
    assertThat(response.items().getFirst().id()).isEqualTo(7L);
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

  @BeforeEach
  void initializeSubject() {
    service =
        new DefaultConsoleAlertRoutingApplicationService(mapper, tenantGuard, metadataResolver);
  }
}
