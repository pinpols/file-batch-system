package io.github.pinpols.batch.console.domain.governance.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.observability.ConsoleSystemParameterService;
import io.github.pinpols.batch.console.domain.governance.application.ConsoleGovernanceParameterPolicy;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局熔断/限流运行时管理：查看当前治理参数、动态调整阈值。
 *
 * <p>底层已有 outbox 熔断、dispatch channel 熔断、console 限流、orchestrator 租户限流。 本接口通过 system_parameter
 * 管理运行时可调参数，各模块消费端按需读取。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/governance")
@PreAuthorize(ConsoleSecurityExpressions.ADMIN_ONLY)
@RequiredArgsConstructor
@Idempotent
public class ConsoleGovernanceController {

  private final ConsoleSystemParameterService parameterService;
  private final ConsoleGovernanceParameterPolicy governancePolicy;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final TenantIdResolver tenantGuard;

  /** 查看当前所有治理参数（含默认值）。 */
  @GetMapping
  public CommonResponse<Map<String, String>> list(@RequestParam("tenantId") String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, String> result = new LinkedHashMap<>();
    governancePolicy
        .defaultValues()
        .forEach((key, defaultValue) ->
            result.put(key, parameterService.getValue(resolved, key).orElse(defaultValue)));
    return responseFactory.success(result);
  }

  /** 动态更新治理参数（改的是全局熔断/限流阈值，误触发会影响所有租户 → 强制幂等）。 */
  @PostMapping
  @Idempotent
  public CommonResponse<Void> update(
      @RequestParam("tenantId") String tenantId, @Valid @RequestBody UpdateGovernanceParam param) {
    // 同 list 走 tenantGuard 校验:不能直接信前端 tenantId,需做格式 / 存在性校验。
    String resolved = tenantGuard.resolveTenant(tenantId);
    if (!governancePolicy.isGovernanceKey(param.key())) {
      return responseFactory.success(null);
    }
    String operator = requestMetadataResolver.current().operatorId();
    parameterService.upsert(
        resolved, param.key(), param.value(), governancePolicy.description(param.key()), operator);
    return responseFactory.success(null);
  }

  /** 重置治理参数为默认值（删除自定义覆盖；破坏性 → 强制幂等）。 */
  @PostMapping("/reset")
  @Idempotent
  public CommonResponse<Void> reset(
      @RequestParam("tenantId") String tenantId, @RequestParam("key") @NotBlank String key) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    parameterService.delete(resolved, key);
    return responseFactory.success(null);
  }

  record UpdateGovernanceParam(
      @NotBlank @Size(max = 128) String key,
      @NotBlank @Size(max = 256) String value) {}
}
