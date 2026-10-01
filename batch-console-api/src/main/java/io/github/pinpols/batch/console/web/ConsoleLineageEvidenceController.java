package io.github.pinpols.batch.console.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.LineageEvidenceResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console BFS lineage 证据链 BFF。 */
@RestController
@RequestMapping("/api/console/lineage")
@RequiredArgsConstructor
public class ConsoleLineageEvidenceController {

  private final ConsoleOrchestratorPort orchestratorProxy;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/result-versions/{id}")
  public CommonResponse<LineageEvidenceResponse> byResultVersion(
      @PathVariable("id") Long id,
      @RequestParam(value = "tenantId", required = false) String tenantId) {
    CommonResponse<LineageEvidenceResponse> resp =
        orchestratorProxy.lineageByResultVersion(id, tenantId);
    return responseFactory.forwardOrchestrator(resp);
  }

  @GetMapping("/effective")
  public CommonResponse<LineageEvidenceResponse> byEffectiveBusinessKey(
      @RequestParam(value = "tenantId", required = false) String tenantId,
      @RequestParam("businessKey") String businessKey) {
    CommonResponse<LineageEvidenceResponse> resp =
        orchestratorProxy.lineageByEffective(tenantId, businessKey);
    return responseFactory.forwardOrchestrator(resp);
  }
}
