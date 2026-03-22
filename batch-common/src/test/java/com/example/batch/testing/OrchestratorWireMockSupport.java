package com.example.batch.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Stubs orchestrator HTTP endpoints used by workers ({@code /internal/workers/**}) so integration
 * tests can run without a real orchestrator process.
 */
public final class OrchestratorWireMockSupport {

    private static volatile WireMockServer server;

    private OrchestratorWireMockSupport() {
    }

    public static void ensureStarted() {
        if (server == null) {
            synchronized (OrchestratorWireMockSupport.class) {
                if (server == null) {
                    WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
                    wm.start();
                    wm.stubFor(any(urlPathMatching("/internal/.*"))
                            .willReturn(aResponse().withStatus(200)));
                    server = wm;
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (server != null) {
                            server.stop();
                            server = null;
                        }
                    }));
                }
            }
        }
    }

    /**
     * Registers {@code batch.orchestrator.base-url} and {@code batch.worker.task-client.base-url}.
     */
    public static void registerOrchestratorBaseUrls(DynamicPropertyRegistry registry) {
        ensureStarted();
        registry.add("batch.orchestrator.base-url", () -> server.baseUrl());
        registry.add("batch.worker.task-client.base-url", () -> server.baseUrl());
    }
}
