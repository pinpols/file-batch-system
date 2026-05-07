package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * ADR-026 演练计划 console 转发：{@code POST /api/console/ops/dry-run/plan} → orchestrator {@code
 * /internal/orchestrator/dry-run/plan}。
 *
 * <p>UI 端按 L1 / L2 / L3 三档发起演练计划查询，不触发 launch。
 */
@RestController
@RequestMapping("/api/console/ops/dry-run")
@RequiredArgsConstructor
public class ConsoleDryRunPlanController {

  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final RestClient.Builder restClientBuilder;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/plan")
  public CommonResponse<Map<String, Object>> plan(@RequestBody Map<String, Object> request) {
    Map<String, Object> resp =
        restClientBuilder
            .baseUrl(orchestratorClientProperties.getBaseUrl())
            .build()
            .post()
            .uri("/internal/orchestrator/dry-run/plan")
            .body(request)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    return responseFactory.success(resp);
  }
}
