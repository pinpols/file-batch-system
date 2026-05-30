package com.example.batch.console.domain.governance.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.domain.observability.service.ConsoleSystemParameterService;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleGovernanceController {

  private static final String PREFIX = "governance.";

  /** Well-known governance parameter keys with default values. */
  private static final Map<String, String> KNOWN_KEYS =
      Map.of(
          "governance.outbox.circuit-breaker.failure-threshold", "3",
          "governance.outbox.circuit-breaker.cooldown-millis", "60000",
          "governance.dispatch.circuit-breaker.failure-threshold", "5",
          "governance.dispatch.circuit-breaker.cooldown-millis", "60000",
          "governance.rate-limit.login-ip-per-minute", "10",
          "governance.rate-limit.sensitive-op-user-per-minute", "30",
          "governance.rate-limit.launch-per-tenant-per-minute", "0",
          "governance.rate-limit.release-per-tenant-per-minute", "0");

  private final ConsoleSystemParameterService parameterService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;

  /** 查看当前所有治理参数（含默认值）。 */
  @GetMapping
  public CommonResponse<Map<String, String>> list(@RequestParam("tenantId") String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, String> result = new LinkedHashMap<>();
    for (var entry : KNOWN_KEYS.entrySet()) {
      Optional<String> value = parameterService.getValue(resolved, entry.getKey());
      result.put(entry.getKey(), value.orElse(entry.getValue()));
    }
    return responseFactory.success(result);
  }

  /** 动态更新治理参数（改的是全局熔断/限流阈值，误触发会影响所有租户 → 强制幂等）。 */
  @PostMapping
  @Idempotent
  public CommonResponse<Void> update(
      @RequestParam("tenantId") String tenantId, @Valid @RequestBody UpdateGovernanceParam param) {
    // 同 list 走 tenantGuard 校验:不能直接信前端 tenantId,需做格式 / 存在性校验。
    String resolved = tenantGuard.resolveTenant(tenantId);
    if (!param.key().startsWith(PREFIX)) {
      return responseFactory.success(null);
    }
    String operator = requestMetadataResolver.current().operatorId();
    String description =
        KNOWN_KEYS.containsKey(param.key())
            ? "Governance parameter: " + param.key()
            : "Custom governance parameter";
    parameterService.upsert(resolved, param.key(), param.value(), description, operator);
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
      @NotBlank @Size(max = 128) String key, @NotBlank @Size(max = 256) String value) {}
}
