package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.OrchestratorInternalRestClient;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** P2 cost profile Console BFF。只做租户收敛和 orchestrator internal API 透传。 */
@RestController
@RequestMapping("/api/console/capacity-profile")
@RequiredArgsConstructor
public class ConsoleCapacityProfileController {

  private final OrchestratorInternalRestClient orchestratorInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<Map<String, Object>> query(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "groupBy", required = false, defaultValue = "TENANT") String groupBy,
      @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    Map<String, Object> resp =
        proxyClient()
            .get()
            .uri(
                uriBuilder -> {
                  var builder =
                      uriBuilder
                          .path("/internal/orchestrator/capacity-profile")
                          .queryParam("tenantId", resolved)
                          .queryParam("groupBy", groupBy)
                          .queryParam("limit", limit);
                  if (from != null && !from.isBlank()) {
                    builder.queryParam("from", from);
                  }
                  if (to != null && !to.isBlank()) {
                    builder.queryParam("to", to);
                  }
                  return builder.build();
                })
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
