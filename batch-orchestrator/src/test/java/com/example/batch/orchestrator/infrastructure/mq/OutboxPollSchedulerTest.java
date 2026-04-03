package com.example.batch.orchestrator.infrastructure.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxPollSchedulerTest {

    @Mock
    private DefaultScheduleForwarder scheduleForwarder;

    @Mock
    private OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;

    private OutboxPollScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPollScheduler(scheduleForwarder, outboxPublishCircuitBreaker);
    }

    @Test
    void shouldAdvanceAndUpdateCircuitBreakerWhenAllowed() {
        when(outboxPublishCircuitBreaker.allowNow()).thenReturn(true);
        when(scheduleForwarder.advance(any())).thenReturn(ScheduleForwarderResult.of(3, 2, 1));

        scheduler.poll();

        ArgumentCaptor<SchedulePlan> planCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
        verify(scheduleForwarder).advance(planCaptor.capture());
        assertThat(planCaptor.getValue()).isNotNull();
        verify(outboxPublishCircuitBreaker).onAdvanceResult(1);
    }

    @Test
    void shouldSkipAdvanceWhenCircuitBreakerDeniesPolling() {
        when(outboxPublishCircuitBreaker.allowNow()).thenReturn(false);

        scheduler.poll();

        verify(scheduleForwarder, never()).advance(any());
        verify(outboxPublishCircuitBreaker, never()).onAdvanceResult(anyInt());
    }
}
