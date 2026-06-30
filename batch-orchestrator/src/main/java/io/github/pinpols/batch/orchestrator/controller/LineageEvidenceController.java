package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.orchestrator.application.service.lineage.LineageEvidenceService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BFS 最小 lineage 证据链内部查询端点。 */
@RestController
@RequestMapping("/internal/orchestrator/lineage")
@RequiredArgsConstructor
public class LineageEvidenceController {

  private final LineageEvidenceService lineageEvidenceService;

  @GetMapping("/result-versions/{id}")
  public CommonResponse<Map<String, Object>> byResultVersion(
      @PathVariable("id") Long id, @RequestParam("tenantId") String tenantId) {
    return CommonResponse.success(lineageEvidenceService.evidenceForResultVersion(tenantId, id));
  }

  @GetMapping("/effective")
  public CommonResponse<Map<String, Object>> byEffectiveBusinessKey(
      @RequestParam("tenantId") String tenantId, @RequestParam("businessKey") String businessKey) {
    return CommonResponse.success(
        lineageEvidenceService.evidenceForEffective(tenantId, businessKey));
  }
}
