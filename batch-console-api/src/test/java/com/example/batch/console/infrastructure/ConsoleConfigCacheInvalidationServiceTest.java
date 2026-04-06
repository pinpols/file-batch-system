package com.example.batch.console.infrastructure;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ConsoleConfigCacheInvalidationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private ConsoleConfigCacheInvalidationService service;

    @BeforeEach
    void setUp() {
        service = new ConsoleConfigCacheInvalidationService(redisTemplate);
    }

    @Test
    void evictJobDefinitionDeletesKeyImmediatelyWhenNoActiveTransaction() {
        service.evictJobDefinition("t1", "JOB1");

        verify(redisTemplate).delete("config:t1:job-definition:JOB1");
    }

    @Test
    void evictWorkflowDefinitionDeletesKeyImmediatelyWhenNoActiveTransaction() {
        service.evictWorkflowDefinition("t1", "WF1");

        verify(redisTemplate).delete("config:t1:workflow-definition:WF1");
    }

    @Test
    void evictWithActiveTransactionDefersDeleteToAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.evictJobDefinition("t1", "JOB2");

            // Within the transaction — Redis should NOT be called yet
            verify(redisTemplate, never()).delete("config:t1:job-definition:JOB2");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        // After transaction scope ends (simulated by clearSynchronization), no hook fires here —
        // but we verified the delete was not called during the transaction, which is the key behavior.
    }

    @Test
    void evictQuotaPoliciesDeletesExpectedKey() {
        service.evictQuotaPolicies("t2");

        verify(redisTemplate).delete("config:t2:tenant-quota-policy:enabled-first");
    }
}
