package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Console BFS lineage 证据链 BFF。 */
@RestController
@RequestMapping("/api/console/lineage")
@RequiredArgsConstructor
public class ConsoleLineageEvidenceController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/result-versions/{id}")
  public CommonResponse<Map<String, Object>> byResultVersion(
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> resp =
        proxyClient()
            .get()
            .uri(
                "/internal/orchestrator/lineage/result-versions/{id}?tenantId={tenantId}",
                id,
                resolved)
            .retrieve()
            .body(unwrapToMap());
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/effective")
  public CommonResponse<Map<String, Object>> byEffectiveBusinessKey(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> resp =
        proxyClient()
            .get()
            .uri(
                "/internal/orchestrator/lineage/effective?tenantId={tenantId}"
                    + "&businessKey={businessKey}",
                resolved,
                businessKey)
            .retrieve()
            .body(unwrapToMap());
    return responseFactory.forwardOrchestrator(resp);
  }

  private RestClient proxyClient() {
    return orchestratorInternalRestClient.build();
  }

  private static ParameterizedTypeReference<Map<String, Object>> unwrapToMap() {
    return new ParameterizedTypeReference<>() {};
  }
}
