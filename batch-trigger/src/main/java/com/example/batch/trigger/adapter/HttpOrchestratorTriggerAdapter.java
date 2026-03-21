package com.example.batch.trigger.adapter;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpOrchestratorTriggerAdapter implements OrchestratorTriggerAdapter {

    private final RestClient orchestratorRestClient;

    public HttpOrchestratorTriggerAdapter(RestClient orchestratorRestClient) {
        this.orchestratorRestClient = orchestratorRestClient;
    }

    @Override
    public LaunchResponse sendTrigger(LaunchRequest request) {
        return orchestratorRestClient.post()
                .uri("/internal/orchestrator/launch")
                .body(request)
                .retrieve()
                .body(LaunchResponse.class);
    }
}
