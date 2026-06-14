package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.persistence.entity.BusinessTenantPlacementEntity;
import com.example.batch.console.domain.audit.support.AuditAction;
import com.example.batch.console.domain.param.BusinessTenantPlacementUpsertParam;
import com.example.batch.console.service.ConsoleBusinessTenantPlacementService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
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
 * biz 租户分片 placement 管理(P2 tenant-routing)。平台 ROLE_ADMIN 维护「哪个租户在哪片」: 列出全量映射、指派/迁片、取消指派(回退
 * hash)。仅维护 tenant→片 key,**不涉及连接账密**(凭据走 secrets)。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/tenant-placements")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleBusinessTenantPlacementController {

  private final ConsoleBusinessTenantPlacementService placementService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @GetMapping
  public CommonResponse<List<BusinessTenantPlacementEntity>> list() {
    return responseFactory.success(placementService.list());
  }

  @PutMapping
  @AuditAction(
      action = "tenant_placement.upsert",
      aggregateType = "tenant_placement",
      aggregateId = "#request.tenantId",
      targetTenantParam = "#request.tenantId")
  public CommonResponse<Void> upsert(@Valid @RequestBody UpsertTenantPlacementRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    placementService.upsert(
        BusinessTenantPlacementUpsertParam.builder()
            .tenantId(request.tenantId())
            .placementKey(request.placementKey())
            .operator(operator)
            .build());
    return responseFactory.success(null);
  }

  @DeleteMapping("/{tenantId}")
  @AuditAction(
      action = "tenant_placement.delete",
      aggregateType = "tenant_placement",
      aggregateId = "#tenantId",
      targetTenantParam = "#tenantId")
  public CommonResponse<Void> delete(
      @PathVariable("tenantId") @NotBlank @Size(max = 64) String tenantId) {
    placementService.delete(tenantId);
    return responseFactory.success(null);
  }

  /** placementKey 约束为 key 字符集(shard-N / silo-xxx 约定),防 typo 误把租户指到不存在的片。 */
  record UpsertTenantPlacementRequest(
      @NotBlank @Size(max = 64) String tenantId,
      @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-z0-9-]+$") String placementKey) {}
}
