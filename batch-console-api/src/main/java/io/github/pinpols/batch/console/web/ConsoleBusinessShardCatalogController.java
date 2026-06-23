package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.entity.BusinessShardCatalogEntity;
import io.github.pinpols.batch.console.domain.param.BusinessShardCatalogUpsertParam;
import io.github.pinpols.batch.console.service.ConsoleBusinessShardCatalogService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * biz 分片目录管理(P2 tenant-routing)。平台 ROLE_ADMIN 登记「有哪些片 + 各片位置 + 状态」, 供前端「分片列表」视图与 placement 指派 key
 * 白名单。**只登记位置不存账密**(secretRef 仅引用名,凭据走 secrets)。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/shard-catalog")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleBusinessShardCatalogController {

  private final ConsoleBusinessShardCatalogService catalogService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @GetMapping
  public CommonResponse<List<BusinessShardCatalogEntity>> list() {
    return responseFactory.success(catalogService.list());
  }

  @PutMapping
  @AuditAction(
      action = "shard_catalog.upsert",
      aggregateType = "shard_catalog",
      aggregateId = "#request.placementKey")
  public CommonResponse<Void> upsert(@Valid @RequestBody UpsertShardCatalogRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    catalogService.upsert(
        BusinessShardCatalogUpsertParam.builder()
            .placementKey(request.placementKey())
            .host(request.host())
            .port(request.port())
            .dbName(request.dbName())
            .secretRef(request.secretRef())
            .poolMaxSize(request.poolMaxSize())
            .enabled(request.enabled())
            .description(request.description())
            .operator(operator)
            .build());
    return responseFactory.success(null);
  }

  @DeleteMapping("/{placementKey}")
  @AuditAction(
      action = "shard_catalog.delete",
      aggregateType = "shard_catalog",
      aggregateId = "#placementKey")
  public CommonResponse<Void> delete(
      @PathVariable("placementKey") @NotBlank @Size(max = 64) String placementKey) {
    catalogService.delete(placementKey);
    return responseFactory.success(null);
  }

  /** 只登记位置 + 状态,**禁带账密**(凭据走 secrets,secretRef 仅引用名)。 */
  record UpsertShardCatalogRequest(
      @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String placementKey,
      @NotBlank @Size(max = 255) String host,
      @Min(1) @Max(65535) int port,
      @NotBlank @Size(max = 64) String dbName,
      @Size(max = 128) String secretRef,
      @Min(1) Integer poolMaxSize,
      boolean enabled,
      @Size(max = 512) String description) {}
}
