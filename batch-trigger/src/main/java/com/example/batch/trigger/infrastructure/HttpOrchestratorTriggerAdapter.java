package com.example.batch.trigger.infrastructure;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class HttpOrchestratorTriggerAdapter implements OrchestratorTriggerAdapter {

    private final RestClient orchestratorRestClient;

    @Override
    public LaunchResponse sendTrigger(LaunchRequest request) {
        return orchestratorRestClient
                .post()
                .uri("/internal/orchestrator/launch")
                .body(request)
                .retrieve()
                .body(LaunchResponse.class);
    }
}
