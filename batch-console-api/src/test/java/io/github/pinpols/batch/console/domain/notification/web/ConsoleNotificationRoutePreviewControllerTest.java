package io.github.pinpols.batch.console.domain.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsoleWebhookSubscriptionMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.SubscriptionRuleMapper;
import io.github.pinpols.batch.console.domain.notification.service.NotificationRoutePreviewService;
import io.github.pinpols.batch.console.domain.notification.service.NotificationRoutePreviewService.Preview;
import io.github.pinpols.batch.console.domain.notification.service.NotificationRoutePreviewService.Rule;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("通知路由预览接口")
class ConsoleNotificationRoutePreviewControllerTest {
  @Test
  @DisplayName("返回安全的路由结果")
  void shouldReturnSafeRoutingProjection_whenPreviewingJobEvent() throws Exception {
    var service = org.mockito.Mockito.mock(NotificationRoutePreviewService.class);
    var metadata = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    when(metadata.responseMeta())
        .thenReturn(new ResponseMeta("request", "trace", BatchDateTimeSupport.utcNow()));
    when(service.preview("tenant", "JOB_FAILED", null, "JOB_A"))
        .thenReturn(new Preview(List.of(new Rule(1L, "failure", "mail", "EMAIL")), List.of()));
    var controller = new ConsoleNotificationRoutePreviewController(
        service, new ConsoleResponseFactory(metadata));
    var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    mockMvc
        .perform(get("/api/console/notifications/route-preview")
            .param("tenantId", "tenant")
            .param("eventType", "JOB_FAILED")
            .param("jobCode", "JOB_A"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rules[0].channelCode").value("mail"))
        .andExpect(jsonPath("$.data.rules[0].channelType").value("EMAIL"))
        .andExpect(jsonPath("$.data.webhooks").isArray());
  }

  @Test
  @DisplayName("仅声明管理员和租户管理员访问权限")
  void shouldDeclareAdminAndTenantAdminBoundary_whenCheckingAuthorization() throws Exception {
    var method = ConsoleNotificationRoutePreviewController.class.getMethod(
        "preview", String.class, String.class, String.class, String.class);
    assertThat(method.getAnnotation(PreAuthorize.class).value())
        .isEqualTo(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN);
  }

  @Test
  @DisplayName("升级告警缺少级别或级别无效时返回 400")
  void shouldRejectSeverityAtHttpBoundary_whenMissingOrInvalid() throws Exception {
    var guard = org.mockito.Mockito.mock(TenantIdResolver.class);
    var rules = org.mockito.Mockito.mock(SubscriptionRuleMapper.class);
    var webhooks = org.mockito.Mockito.mock(ConsoleWebhookSubscriptionMapper.class);
    when(guard.resolveTenant("tenant")).thenReturn("tenant");
    var service = new NotificationRoutePreviewService(guard, rules, webhooks);
    var metadata = org.mockito.Mockito.mock(ConsoleRequestMetadataResolver.class);
    when(metadata.responseMeta())
        .thenReturn(new ResponseMeta("request", "trace", BatchDateTimeSupport.utcNow()));
    var responseFactory = new ConsoleResponseFactory(metadata);
    var controller = new ConsoleNotificationRoutePreviewController(service, responseFactory);
    var mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(ConsoleApiExceptionHandler.forStandaloneTest(responseFactory))
        .build();
    mockMvc
        .perform(get("/api/console/notifications/route-preview")
            .param("tenantId", "tenant")
            .param("eventType", "ALERT_ESCALATED"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/console/notifications/route-preview")
            .param("tenantId", "tenant")
            .param("eventType", "ALERT_ESCALATED")
            .param("severity", "WARNING"))
        .andExpect(status().isBadRequest());
    org.mockito.Mockito.verifyNoInteractions(rules, webhooks);
  }
}
