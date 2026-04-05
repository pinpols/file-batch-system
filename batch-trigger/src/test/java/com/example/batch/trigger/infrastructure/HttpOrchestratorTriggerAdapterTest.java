package com.example.batch.trigger.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.skyscreamer.jsonassert.JSONAssert.assertEquals;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.enums.TriggerType;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class HttpOrchestratorTriggerAdapterTest {

    private MockWebServer server;
    private HttpOrchestratorTriggerAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(clientHttpRequestFactory(800))
                .build();
        adapter = new HttpOrchestratorTriggerAdapter(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldReturnLaunchResponseOn200() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"instanceNo\":\"inst-1\",\"traceId\":\"trace-1\"}"));

        LaunchRequest request = sampleRequest();
        var response = adapter.sendTrigger(request);

        assertThat(response.instanceNo()).isEqualTo("inst-1");
        assertThat(response.traceId()).isEqualTo("trace-1");
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/internal/orchestrator/launch");
    }

    @Test
    void shouldPropagate4xxAsRestClientResponseException() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"INVALID_ARGUMENT\",\"message\":\"bad\"}"));

        assertThatThrownBy(() -> adapter.sendTrigger(sampleRequest()))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(ex -> assertThat(((RestClientResponseException) ex).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void shouldFailOnReadTimeoutWhenOrchestratorIsSlow() throws IOException {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        RestClient shortTimeoutClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(clientHttpRequestFactory(100))
                .build();
        HttpOrchestratorTriggerAdapter shortTimeoutAdapter = new HttpOrchestratorTriggerAdapter(shortTimeoutClient);

        server.enqueue(new MockResponse()
                .setBodyDelay(3, TimeUnit.SECONDS)
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"instanceNo\":\"x\",\"traceId\":\"y\"}"));

        assertThatThrownBy(() -> shortTimeoutAdapter.sendTrigger(sampleRequest()))
                .isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }

    @Test
    void shouldPostJsonBodyMatchingLaunchRequest() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"instanceNo\":\"i\",\"traceId\":\"t\"}"));

        LaunchRequest request = new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                java.time.LocalDate.of(2026, 3, 28),
                TriggerType.API,
                "req-1",
                "tr-1",
                Map.of("k", "v"));

        adapter.sendTrigger(request);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/internal/orchestrator/launch");
        String body = recorded.getBody().readUtf8();
        assertEquals(
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
                body,
                false);
    }

    private static LaunchRequest sampleRequest() {
        return new LaunchRequest(
                "t1",
                "IMPORT_JOB",
                java.time.LocalDate.of(2026, 3, 28),
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
