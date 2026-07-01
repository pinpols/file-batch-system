package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.entity.AssetFreshnessPolicyEntity;
import io.github.pinpols.batch.console.domain.param.AssetFreshnessPolicyUpsertParam;
import io.github.pinpols.batch.console.service.ConsoleAssetFreshnessPolicyService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** JOB asset freshness policy 管理。 */
@RestController
@Validated
@RequestMapping("/api/console/asset-freshness-policies")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
public class ConsoleAssetFreshnessPolicyController {

  private final ConsoleAssetFreshnessPolicyService policyService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<List<AssetFreshnessPolicyEntity>> list(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "assetCode", required = false) String assetCode,
      @RequestParam(value = "enabled", required = false) Boolean enabled,
      @RequestParam(value = "limit", required = false, defaultValue = "100") @Min(1) @Max(500)
          Integer limit) {
    return responseFactory.success(policyService.list(tenantId, assetCode, enabled, limit));
  }

  @GetMapping("/{id}")
  public CommonResponse<AssetFreshnessPolicyEntity> get(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @PathVariable("id") Long id) {
    return responseFactory.success(policyService.get(tenantId, id));
  }

  @PostMapping
  public CommonResponse<Void> create(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @Valid @RequestBody UpsertAssetFreshnessPolicyRequest request) {
    policyService.upsert(request.toParam(null, tenantId));
    return responseFactory.success(null);
  }

  @PutMapping("/{id}")
  public CommonResponse<Void> update(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @PathVariable("id") Long id,
      @Valid @RequestBody UpsertAssetFreshnessPolicyRequest request) {
    policyService.upsert(request.toParam(id, tenantId));
    return responseFactory.success(null);
  }

  @PatchMapping("/{id}/enabled")
  public CommonResponse<Void> setEnabled(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @PathVariable("id") Long id,
      @Valid @RequestBody ToggleAssetFreshnessPolicyRequest request) {
    policyService.setEnabled(tenantId, id, request.enabled());
    return responseFactory.success(null);
  }

  record UpsertAssetFreshnessPolicyRequest(
      @NotBlank @Size(max = 128) String assetCode,
      @Size(max = 32) String assetType,
      @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime expectedByLocalTime,
      @Size(max = 64) String timezone,
      @Min(0) Integer staleAfterSeconds,
      @Min(1) @Max(31) Integer lookbackDays,
      @Size(max = 16) String severity,
      Boolean enabled) {

    AssetFreshnessPolicyUpsertParam toParam(Long id, String tenantId) {
      return AssetFreshnessPolicyUpsertParam.builder()
          .id(id)
          .tenantId(tenantId)
          .assetCode(assetCode)
          .assetType(assetType)
          .expectedByLocalTime(expectedByLocalTime)
          .timezone(timezone)
          .staleAfterSeconds(staleAfterSeconds)
          .lookbackDays(lookbackDays)
          .severity(severity)
          .enabled(enabled)
          .build();
    }
  }

  record ToggleAssetFreshnessPolicyRequest(boolean enabled) {}
}
