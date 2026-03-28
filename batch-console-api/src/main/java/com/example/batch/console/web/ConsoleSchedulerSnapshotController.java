package com.example.batch.console.web;

import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotResponse;
import com.example.batch.common.dto.CommonResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@Validated
@RequestMapping("/api/console/scheduler")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleSchedulerSnapshotController {

    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final RestClient.Builder restClientBuilder;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/snapshot")
    public CommonResponse<ConsoleSchedulerSnapshotResponse> live(@RequestParam("tenantId") String tenantId) {
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ConsoleSchedulerSnapshotResponse body = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/scheduler/snapshot")
                        .queryParam("tenantId", tenantId)
                        .build())
                .retrieve()
                .body(ConsoleSchedulerSnapshotResponse.class);
        return responseFactory.success(body);
    }

    @GetMapping("/snapshot/history")
    public CommonResponse<List<ConsoleSchedulerSnapshotHistoryResponse>> history(@RequestParam("tenantId") String tenantId,
                                                                                @RequestParam(value = "limit", defaultValue = "20") int limit) {
        RestClient client = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        List<ConsoleSchedulerSnapshotHistoryResponse> body = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/scheduler/snapshot/history")
                        .queryParam("tenantId", tenantId)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<ConsoleSchedulerSnapshotHistoryResponse>>() {
                });
        return responseFactory.success(body);
    }
}
