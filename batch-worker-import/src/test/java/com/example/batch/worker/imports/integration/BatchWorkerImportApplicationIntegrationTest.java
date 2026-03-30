package com.example.batch.worker.imports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.imports.BatchWorkerImportApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Loads the import worker with Testcontainers Postgres (platform + biz), Kafka, MinIO, and a stub
 * orchestrator HTTP endpoint.
 */
@SpringBootTest(classes = BatchWorkerImportApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchWorkerImportApplicationIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void orchestratorStub(DynamicPropertyRegistry registry) {
        OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
    }

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }
}
