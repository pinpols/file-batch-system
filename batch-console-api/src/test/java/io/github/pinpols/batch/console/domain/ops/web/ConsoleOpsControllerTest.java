package io.github.pinpols.batch.console.domain.ops.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.application.ops.ConsoleKafkaLagQueryPort;
import io.github.pinpols.batch.console.application.ops.ConsoleOpsSummaryPort;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOutboxOpsApplicationService;
import io.github.pinpols.batch.console.domain.ops.application.contract.response.ConsoleOutboxRepublishResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.view.ConsoleOpsSummaryResponse;
import io.github.pinpols.batch.console.support.web.ConsoleApiExceptionHandler;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@DisplayName("运维概览接口:汇总响应结构与字段回填正确, 且允许租户用户读取")
@ExtendWith(MockitoExtension.class)
class ConsoleOpsControllerTest {

  @Mock
  private ConsoleOpsSummaryPort opsApplicationService;

  @Mock
  private ConsoleOutboxOpsApplicationService outboxOpsService;

  @Mock
  private ConsoleKafkaLagQueryPort kafkaLagQueryService;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    ConsoleResponseFactory responseFactory = new ConsoleResponseFactory(requestMetadataResolver);
    ConsoleApiExceptionHandler exceptionHandler =
        ConsoleApiExceptionHandler.forStandaloneTest(responseFactory);

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc = MockMvcBuilders.standaloneSetup(new ConsoleOpsController(
            opsApplicationService, outboxOpsService, responseFactory, kafkaLagQueryService))
        .setControllerAdvice(exceptionHandler)
        .setValidator(validator)
        .build();
  }

  @Test
  @DisplayName("汇总查询:返回成功响应并回填租户与各项计数")
  void shouldReturn200AndCommonResponseStructureOnSuccess() throws Exception {
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(opsApplicationService.summary(anyString()))
        .thenReturn(ConsoleOpsSummaryResponse.builder()
            .tenantId("t1")
            .pendingApprovals(1L)
            .openAlerts(2L)
            .criticalAlerts(3L)
            .runningJobs(4L)
            .failedJobs(5L)
            .slaBreaches(6L)
            .onlineWorkers(7L)
            .drainingWorkers(8L)
            .offlineWorkers(9L)
            .outboxRetryBacklog(10L)
            .outboxDeliveryFailures(11L)
            .build());

    mockMvc
        .perform(get("/api/console/ops/summary").param("tenantId", "t1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data.tenantId").value("t1"))
        .andExpect(jsonPath("$.data.pendingApprovals").value(1))
        .andExpect(jsonPath("$.data.openAlerts").value(2))
        .andExpect(jsonPath("$.data.criticalAlerts").value(3));
  }

  @Test
  @DisplayName("重投接口精确接收超过 JS 安全整数的 64 位事件 ID")
  void shouldPreserveLongIds_whenRepublishingOutbox() throws Exception {
    List<Long> ids = List.of(9007199254740993L, Long.MAX_VALUE);
    when(requestMetadataResolver.responseMeta())
        .thenReturn(new ResponseMeta("req-1", "trace-1", BatchDateTimeSupport.utcNow()));
    when(outboxOpsService.republish("t1", ids))
        .thenReturn(new ConsoleOutboxRepublishResponse("t1", 2, 1));
    mockMvc
        .perform(post("/api/console/ops/outbox/republish")
            .param("tenantId", "t1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("[9007199254740993,9223372036854775807]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.resetCount").value(1));
    verify(outboxOpsService).republish("t1", ids);
  }

  @Test
  @DisplayName("读权限:汇总方法的授权注解包含租户用户角色")
  void shouldAllowTenantUserToReadSummary() throws Exception {
    PreAuthorize authorization = ConsoleOpsController.class
        .getMethod("summary", String.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(authorization.value()).contains("ROLE_TENANT_USER");
  }

  @ParameterizedTest
  @CsvSource({
    "ROLE_AUDITOR,false",
    "ROLE_TENANT_USER,false",
    "ROLE_ADMIN,true",
    "ROLE_TENANT_ADMIN,true"
  })
  @DisplayName("真实方法鉴权：只读角色不能清理或重投，管理员写权限保留")
  void shouldEnforceOutboxWriteAuthorization(String role, boolean allowed) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(MethodSecurityConfiguration.class);
      context.registerBean(
          ConsoleOpsController.class,
          () -> new ConsoleOpsController(
              opsApplicationService,
              outboxOpsService,
              new ConsoleResponseFactory(requestMetadataResolver),
              kafkaLagQueryService));
      context.refresh();
      ConsoleOpsController secured = context.getBean(ConsoleOpsController.class);
      List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(role));
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated("operator", "unused", authorities));
      List<Long> eventIds = List.of(41L);
      if (allowed) {
        secured.outboxCleanup("t1", 7);
        secured.outboxRepublish("t1", eventIds);
        verify(outboxOpsService).cleanup("t1", 7);
        verify(outboxOpsService).republish("t1", eventIds);
      } else {
        assertThatThrownBy(() -> secured.outboxCleanup("t1", 7))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> secured.outboxRepublish("t1", eventIds))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(outboxOpsService);
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfiguration {}
}
