package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.Idempotent;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigCopyRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigPreviewRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigDiffPreviewResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigMatrixResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户配置批量初始化与跨租户复制 REST 端点。 */
@RestController
@Validated
@RequestMapping("/api/console/config")
@RequiredArgsConstructor
public class ConsoleTenantConfigInitController {

  private final ConsoleTenantConfigInitApplicationService applicationService;
  private final ConsoleTenantConfigCopyService copyService;
  private final ConsoleResponseFactory responseFactory;

  /**
   * 批量初始化或更新多个租户的配置。
   *
   * <p>mode=SKIP_EXISTING（默认）：已存在的配置不覆盖，仅创建缺失项。 mode=UPSERT：存在则更新，不存在则创建。
   * dryRun=true：只做查询和校验，不执行写入。
   */
  @PostMapping("/tenant-init")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @Idempotent
  public CommonResponse<TenantConfigBatchInitResponse> batchInit(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TenantConfigBatchInitRequest request,
      Authentication authentication) {
    String operator = resolveOperator(authentication);
    String batchOperationId = UUID.randomUUID().toString();
    return responseFactory.success(
        applicationService.batchInit(request, operator, batchOperationId));
  }

  /**
   * 跨租户配置复制。
   *
   * <p>从源租户读取配置，转换为 Spec 后推送到目标租户列表。
   */
  @PostMapping("/tenant-copy")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  @Idempotent
  public CommonResponse<TenantConfigBatchInitResponse> tenantCopy(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody TenantConfigCopyRequest request,
      Authentication authentication) {
    String operator = resolveOperator(authentication);
    String batchOperationId = UUID.randomUUID().toString();
    return responseFactory.success(copyService.copy(request, operator, batchOperationId));
  }

  /** 跨租户复制前预览 add/update/delete-candidate 和影响面，不执行写库。 */
  @PostMapping("/tenant-copy/preview")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigDiffPreviewResponse> tenantCopyPreview(
      @Valid @RequestBody TenantConfigPreviewRequest request) {
    return responseFactory.success(copyService.preview(request));
  }

  /** base package + tenant overlay 预览：返回每个目标租户相对基础租户的差异包。 */
  @PostMapping("/tenant-overlay/preview")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigDiffPreviewResponse> tenantOverlayPreview(
      @Valid @RequestBody TenantConfigPreviewRequest request) {
    return responseFactory.success(copyService.previewOverlay(request));
  }

  /** 同一 job 跨租户矩阵：用于发现 schedule/queue/template/channel 等漂移。 */
  @PostMapping("/tenant-config-matrix")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<TenantConfigMatrixResponse> tenantConfigMatrix(
      @Valid @RequestBody TenantConfigMatrixRequest request) {
    return responseFactory.success(copyService.matrix(request));
  }

  private String resolveOperator(Authentication authentication) {
    if (authentication == null) {
      return "system";
    }
    if (authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      return principal.username();
    }
    return authentication.getName();
  }
}
