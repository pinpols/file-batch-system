package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleConfigApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.web.Idempotent;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.request.config.ConfigReleaseActionRequest;
import com.example.batch.console.web.request.config.ConfigReleaseUpsertRequest;
import com.example.batch.console.web.request.ops.SecretVersionRotateRequest;
import com.example.batch.console.web.response.config.ConsoleConfigChangeLogResponse;
import com.example.batch.console.web.response.config.ConsoleConfigReleaseResponse;
import com.example.batch.console.web.response.ops.ConsoleSecretVersionResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台配置中心 REST：发布单、灰度、回滚、密钥轮换与变更日志（方法级权限见注解）。 */
@RestController
@Validated
@RequestMapping("/api/console/config")
@RequiredArgsConstructor
@Idempotent
public class ConsoleConfigController {

  private final ConsoleConfigApplicationService applicationService;
  private final ConsoleResponseFactory responseFactory;

  /** 查询配置发布单列表。 */
  @GetMapping("/releases")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<List<ConsoleConfigReleaseResponse>> configReleases(
      @Valid @ModelAttribute ConfigReleaseQueryRequest request) {
    return responseFactory.success(applicationService.configReleases(request));
  }

  /** 创建配置发布单草稿。 */
  @PostMapping("/releases")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<Long> createConfigRelease(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigReleaseUpsertRequest request) {
    return responseFactory.success(applicationService.createConfigRelease(request));
  }

  /** 全量发布配置。 */
  @PostMapping("/releases/{releaseId}/publish")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> publishConfigRelease(
      @PathVariable Long releaseId,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigReleaseActionRequest request) {
    return responseFactory.success(applicationService.publishConfigRelease(releaseId, request));
  }

  /** 灰度发布配置。 */
  @PostMapping("/releases/{releaseId}/gray")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> grayConfigRelease(
      @PathVariable Long releaseId,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigReleaseActionRequest request) {
    return responseFactory.success(applicationService.grayConfigRelease(releaseId, request));
  }

  /** 回滚配置发布。 */
  @PostMapping("/releases/{releaseId}/rollback")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<String> rollbackConfigRelease(
      @PathVariable Long releaseId,
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody ConfigReleaseActionRequest request) {
    return responseFactory.success(applicationService.rollbackConfigRelease(releaseId, request));
  }

  /** 查询密钥版本。 */
  @GetMapping("/secrets")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<List<ConsoleSecretVersionResponse>> secretVersions(
      @Valid @ModelAttribute SecretVersionQueryRequest request) {
    return responseFactory.success(applicationService.secretVersions(request));
  }

  /** 轮换密钥。 */
  @PostMapping("/secrets/rotate")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public CommonResponse<Long> rotateSecretVersion(
      @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
      @Valid @RequestBody SecretVersionRotateRequest request) {
    return responseFactory.success(applicationService.rotateSecretVersion(request));
  }

  /** 查询配置变更日志。 */
  @GetMapping("/change-logs")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<List<ConsoleConfigChangeLogResponse>> configChangeLogs(
      @Valid @ModelAttribute ConfigChangeLogQueryRequest request) {
    return responseFactory.success(applicationService.configChangeLogs(request));
  }

  @GetMapping("/releases/{releaseId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ConsoleConfigReleaseResponse> configReleaseDetail(
      @PathVariable Long releaseId, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(applicationService.configReleaseDetail(tenantId, releaseId));
  }

  /** 查询配置项的依赖关系。 */
  @GetMapping("/dependencies")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<Map<String, Object>> configDependencies(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("configType") String configType,
      @RequestParam("configCode") String configCode) {
    return responseFactory.success(
        applicationService.configDependencies(tenantId, configType, configCode));
  }

  /** 对比两个配置发布版本的差异。 */
  @GetMapping("/releases/diff")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<Map<String, Object>> diffConfigReleases(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("releaseIdA") Long releaseIdA,
      @RequestParam("releaseIdB") Long releaseIdB) {
    return responseFactory.success(
        applicationService.diffConfigReleases(tenantId, releaseIdA, releaseIdB));
  }

  @GetMapping("/secrets/{secretVersionId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
  public CommonResponse<ConsoleSecretVersionResponse> secretVersionDetail(
      @PathVariable Long secretVersionId, @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        applicationService.secretVersionDetail(tenantId, secretVersionId));
  }
}
