package io.github.pinpols.batch.console.domain.rbac.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.ConsoleOidcIdentityRequest;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleOidcIdentityService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@DisplayName("OIDC 控制器")
class ConsoleOidcControllerTest {

  private ConsoleOidcProperties properties;
  private ConsoleOidcIdentityService identityService;
  private ConsoleResponseFactory responseFactory;
  private ConsoleOidcController controller;

  @BeforeEach
  void setUp() {
    properties = new ConsoleOidcProperties();
    properties.setRegistrationId("pilot-tenant");
    identityService = mock(ConsoleOidcIdentityService.class);
    responseFactory = mock(ConsoleResponseFactory.class);
    controller = new ConsoleOidcController(properties, identityService, responseFactory);
  }

  @Test
  @DisplayName("登录页 provider 响应不暴露 issuer 或客户端凭据")
  void shouldExposeOnlyLoginProviderAvailability() {
    properties.setEnabled(true);

    controller.provider();

    verify(responseFactory).success(any());
  }

  @Test
  @DisplayName("身份管理委托租户范围，绑定人来自已认证主体")
  void shouldDelegateIdentityOperationsWithTenantAndActor() {
    Authentication authentication = new UsernamePasswordAuthenticationToken(
        new ConsolePrincipal("platform-admin", "tenant-a", Set.of("ROLE_ADMIN")), "credentials");
    ConsoleOidcIdentityRequest request = new ConsoleOidcIdentityRequest("local-user", "subject-1");

    controller.list("tenant-a");
    controller.bind("tenant-a", request, authentication);
    controller.unbind(17L, "tenant-a");

    verify(identityService).list("tenant-a");
    verify(identityService).bind("tenant-a", request, "platform-admin");
    verify(identityService).unbind("tenant-a", 17L);
  }

  @Test
  @DisplayName("非平台主体按认证名称记录绑定操作人")
  void shouldResolveFallbackAuthenticationName() {
    Authentication authentication =
        new UsernamePasswordAuthenticationToken("fallback-admin", "credentials");
    ConsoleOidcIdentityRequest request = new ConsoleOidcIdentityRequest("local-user", "subject-2");

    controller.bind("tenant-a", request, authentication);

    verify(identityService).bind("tenant-a", request, "fallback-admin");
  }
}
