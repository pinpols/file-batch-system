package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Asset partition console 查询 API。
 *
 * <p>Console 只做租户边界校验与统一响应包装;依赖裁决、EFFECTIVE 分区选择、版本明细由 orchestrator readiness 服务负责，避免前台按
 * result_version / asset_partition 自行拼接语义。
 */
@RestController
@RequestMapping("/api/console/asset-partitions")
@RequiredArgsConstructor
public class ConsoleAssetPartitionController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/readiness")
  public CommonResponse<Map<String, Object>> readiness(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("jobCode") String jobCode,
      @RequestParam("bizDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> resp =
        proxyClient()
            .get()
            .uri(
                "/internal/readiness/job?tenantId={tenantId}&jobCode={jobCode}&bizDate={bizDate}",
                resolved,
                jobCode,
                bizDate)
            .retrieve()
            .body(unwrapToMap());
    return responseFactory.success(resp == null ? Map.of() : resp);
  }

  private RestClient proxyClient() {
    return orchestratorInternalRestClient.build();
  }

  private static ParameterizedTypeReference<Map<String, Object>> unwrapToMap() {
    return new ParameterizedTypeReference<>() {};
  }
}
