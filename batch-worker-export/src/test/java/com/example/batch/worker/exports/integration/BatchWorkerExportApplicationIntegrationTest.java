package com.example.batch.worker.exports.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.exports.BatchWorkerExportApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = BatchWorkerExportApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchWorkerExportApplicationIntegrationTest extends AbstractIntegrationTest {

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
