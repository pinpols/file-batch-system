package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.rbac.entity.ApiKeyEntity;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleApiKeyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API Key 自助管理：租户创建/查看/吊销 API Key。 */
@RestController
@Validated
@RequestMapping("/api/console/api-keys")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleApiKeyController {

  private final ConsoleApiKeyService apiKeyService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  /** 列出租户下所有 API Key（不含明文密钥）。 */
  @GetMapping
  public CommonResponse<List<ApiKeyEntity>> list(@RequestParam("tenantId") String tenantId) {
    return responseFactory.success(apiKeyService.list(tenantId));
  }

  /** 查看 API Key 详情。 */
  @GetMapping("/{id}")
  public CommonResponse<ApiKeyEntity> detail(
      @RequestParam("tenantId") String tenantId, @PathVariable Long id) {
    return responseFactory.success(apiKeyService.detail(tenantId, id));
  }

  /** 创建 API Key，返回明文密钥（仅此一次可见）。双击/重试可能创建多把密钥 → 强制幂等。 */
  @PostMapping
  @Idempotent
  @AuditAction(
      action = "apiKey.create",
      aggregateType = "api_key",
      recordParams = false,
      targetTenantParam = "#tenantId")
  public CommonResponse<Map<String, Object>> create(
      @RequestParam("tenantId") String tenantId, @Valid @RequestBody CreateApiKeyRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    ConsoleApiKeyService.CreateResult result =
        apiKeyService.create(
            tenantId, request.keyName(), request.scopes(), request.expiresAt(), operator);
    return responseFactory.success(
        Map.of(
            "id", result.entity().getId(),
            "keyName", result.entity().getKeyName(),
            "keyPrefix", result.entity().getKeyPrefix(),
            "rawKey", result.rawKey(),
            "createdAt", result.entity().getCreatedAt()));
  }

  /** 吊销 API Key。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @AuditAction(
      action = "apiKey.revoke",
      aggregateType = "api_key",
      aggregateId = "#id",
      targetTenantParam = "#tenantId")
  public CommonResponse<Void> revoke(
      @RequestParam("tenantId") String tenantId, @PathVariable Long id) {
    String operator = requestMetadataResolver.current().operatorId();
    apiKeyService.revoke(tenantId, id, operator);
    return responseFactory.success(null);
  }

  record CreateApiKeyRequest(
      @NotBlank @Size(max = 128) String keyName,
      @Size(max = 512) String scopes,
      Instant expiresAt) {}
}
