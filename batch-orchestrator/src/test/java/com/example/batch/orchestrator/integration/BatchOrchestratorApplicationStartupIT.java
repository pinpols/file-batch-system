package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Integration smoke: Spring context with real Postgres (platform + business), Kafka, and MinIO.
 *
 * <p>Inherits {@link AbstractIntegrationTest} — do not duplicate {@code @BatchIntegrationTest} or
 * container setup here.
 */
@SpringBootTest(classes = BatchOrchestratorApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BatchOrchestratorApplicationStartupIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }
}
