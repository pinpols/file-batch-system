package io.github.pinpols.batch.console.domain.rbac.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.AccountRow;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.ApplyResult;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.Operation;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserBatchProvisioningService.Preview;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.audit.AuditAction;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 账号批量开户的传输层；租户与角色裁决在服务层重新执行。 */
@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_OR_TENANT_ADMIN)
@RequestMapping("/api/console/users/batch")
public class ConsoleUserBatchProvisioningController {

  private final ConsoleUserBatchProvisioningService service;
  private final ConsoleResponseFactory responseFactory;

  public record PatchRequest(
      @NotNull Integer version, @NotNull AccountRow row) {}

  public record ApplyRequest(
      @NotNull Integer version, @NotNull UUID requestId) {}

  @GetMapping("/template")
  public ResponseEntity<byte[]> template() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=accounts-template.xlsx")
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(service.template());
  }

  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @AuditAction(action = "user.batchPreview", aggregateType = "user_account", recordParams = false)
  public CommonResponse<Preview> preview(@RequestParam("file") MultipartFile file)
      throws IOException {
    return responseFactory.success(service.preview(file));
  }

  @PostMapping("/preview/{token}/patch")
  @AuditAction(
      action = "user.batchPreviewPatch",
      aggregateType = "user_account",
      recordParams = false)
  public CommonResponse<Preview> patch(
      @PathVariable String token, @Valid @RequestBody PatchRequest request) {
    return responseFactory.success(service.patch(token, request.version(), request.row()));
  }

  @PostMapping("/apply/{token}")
  @Idempotent
  @AuditAction(action = "user.batchCreate", aggregateType = "user_account", recordParams = false)
  public CommonResponse<ApplyResult> apply(
      @PathVariable String token, @Valid @RequestBody ApplyRequest request) {
    return responseFactory.success(service.apply(token, request.version(), request.requestId()));
  }

  @GetMapping("/operations/{operationId}")
  public CommonResponse<Operation> operation(@PathVariable UUID operationId) {
    return responseFactory.success(service.operation(operationId));
  }

  @GetMapping("/operations")
  public CommonResponse<Operation> findOperation(@RequestParam UUID requestId) {
    return responseFactory.success(service.findByRequestId(requestId));
  }
}
