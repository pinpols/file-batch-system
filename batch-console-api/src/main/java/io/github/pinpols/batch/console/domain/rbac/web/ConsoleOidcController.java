package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.ConsoleOidcIdentityRequest;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleOidcIdentityResponse;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleOidcProviderResponse;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleOidcIdentityService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 暴露登录页所需的 OIDC 入口，并提供全局管理员的显式身份绑定维护。 */
@RestController
@Validated
@RequestMapping("/api/console/auth/oidc")
@RequiredArgsConstructor
public class ConsoleOidcController {

  private final ConsoleOidcProperties properties;
  private final ConsoleOidcIdentityService identityService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/provider")
  public CommonResponse<ConsoleOidcProviderResponse> provider() {
    return responseFactory.success(new ConsoleOidcProviderResponse(
        properties.isEnabled(), properties.isEnabled() ? properties.getRegistrationId() : null));
  }

  @GetMapping("/identities")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<List<ConsoleOidcIdentityResponse>> list(@RequestParam String tenantId) {
    return responseFactory.success(identityService.list(tenantId));
  }

  @PostMapping("/identities")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "auth.oidc.identity.bind",
      aggregateType = "oidc_identity",
      targetTenantParam = "#tenantId",
      recordParams = false)
  public CommonResponse<ConsoleOidcIdentityResponse> bind(
      @RequestParam String tenantId,
      @Valid @RequestBody ConsoleOidcIdentityRequest request,
      Authentication authentication) {
    return responseFactory.success(
        identityService.bind(tenantId, request, resolveUsername(authentication)));
  }

  @DeleteMapping("/identities/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "auth.oidc.identity.unbind",
      aggregateType = "oidc_identity",
      aggregateId = "#id",
      targetTenantParam = "#tenantId",
      recordParams = false)
  public CommonResponse<Void> unbind(@PathVariable long id, @RequestParam String tenantId) {
    identityService.unbind(tenantId, id);
    return responseFactory.success(null);
  }

  private String resolveUsername(Authentication authentication) {
    if (EmptyChecks.isNotNull(authentication)
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    return EmptyChecks.isNull(authentication) ? null : authentication.getName();
  }
}
