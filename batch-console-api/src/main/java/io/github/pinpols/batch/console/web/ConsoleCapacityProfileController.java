package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.CapacityProfileResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** P2 cost profile Console BFF。只做租户收敛和 orchestrator internal API 透传。 */
@RestController
@RequestMapping("/api/console/capacity-profile")
@RequiredArgsConstructor
public class ConsoleCapacityProfileController {

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping
  public CommonResponse<CapacityProfileResponse> query(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "groupBy", required = false, defaultValue = "TENANT") String groupBy,
      @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
    CommonResponse<CapacityProfileResponse> resp =
        orchestratorProxy.capacityProfile(tenantId, from, to, groupBy, limit);
    return responseFactory.forwardOrchestrator(resp);
  }
}
