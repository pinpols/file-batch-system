package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Console API with real Postgres, Kafka, MinIO; Flyway applies {@code docs/sql/flyway} on the platform DB.
 */
@SpringBootTest(classes = BatchConsoleApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BatchConsoleApiApplicationIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }
}
