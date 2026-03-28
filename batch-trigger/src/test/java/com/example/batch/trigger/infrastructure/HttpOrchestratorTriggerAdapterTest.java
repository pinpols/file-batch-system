package com.example.batch.trigger.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class HttpOrchestratorTriggerAdapterTest {

    private WireMockServer wireMockServer;
    private HttpOrchestratorTriggerAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor(wireMockServer.port());
        RestClient client = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(clientHttpRequestFactory(800))
                .build();
        adapter = new HttpOrchestratorTriggerAdapter(client);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldReturnLaunchResponseOn200() {
        wireMockServer.stubFor(post(urlEqualTo("/internal/orchestrator/launch"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceNo\":\"inst-1\",\"traceId\":\"trace-1\"}")));

        LaunchRequest request = sampleRequest();
        var response = adapter.sendTrigger(request);

        assertThat(response.instanceNo()).isEqualTo("inst-1");
        assertThat(response.traceId()).isEqualTo("trace-1");
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/internal/orchestrator/launch")));
    }

    @Test
    void shouldPropagate4xxAsRestClientResponseException() {
        wireMockServer.stubFor(post(urlEqualTo("/internal/orchestrator/launch"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INVALID_ARGUMENT\",\"message\":\"bad\"}")));

        assertThatThrownBy(() -> adapter.sendTrigger(sampleRequest()))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(ex -> assertThat(((RestClientResponseException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void shouldFailOnReadTimeoutWhenOrchestratorIsSlow() {
        RestClient shortTimeoutClient = RestClient.builder()
                .baseUrl(wireMockServer.baseUrl())
                .requestFactory(clientHttpRequestFactory(100))
                .build();
        HttpOrchestratorTriggerAdapter shortTimeoutAdapter = new HttpOrchestratorTriggerAdapter(shortTimeoutClient);

        wireMockServer.stubFor(post(urlEqualTo("/internal/orchestrator/launch"))
                .willReturn(aResponse()
                        .withFixedDelay(3000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceNo\":\"x\",\"traceId\":\"y\"}")));

        assertThatThrownBy(() -> shortTimeoutAdapter.sendTrigger(sampleRequest()))
                .isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }

    @Test
    void shouldPostJsonBodyMatchingLaunchRequest() {
        wireMockServer.stubFor(post(urlEqualTo("/internal/orchestrator/launch"))
                .withRequestBody(equalToJson(
                        """
                                {
                                  "tenantId": "t1",
                                  "jobCode": "IMPORT_JOB",
                                  "bizDate": "2026-03-28",
                                  "triggerType": "API",
                                  "requestId": "req-1",
                                  "traceId": "tr-1",
                                  "params": {"k": "v"}
                                }
                                """,
                        true,
                        true))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"instanceNo\":\"i\",\"traceId\":\"t\"}")));

        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 28),
                TriggerType.API,
                "req-1",
                "tr-1",
                Map.of("k", "v"));

        adapter.sendTrigger(request);

        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/internal/orchestrator/launch")));
    }

    private static LaunchRequest sampleRequest() {
        return new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                LocalDate.of(2026, 3, 28),
                TriggerType.SCHEDULED,
                "req-x",
                "tr-x",
                Map.of());
    }

    private static SimpleClientHttpRequestFactory clientHttpRequestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
