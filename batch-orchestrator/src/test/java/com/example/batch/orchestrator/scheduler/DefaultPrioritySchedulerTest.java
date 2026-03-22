package com.example.batch.orchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.SchedulingPriorityBand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPrioritySchedulerTest {

    private DefaultPriorityScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultPriorityScheduler();
    }

    // --- resolvePriority ---

    @Test
    void shouldReturnDefaultPriorityFiveWhenRequestIsNull() {
        assertThat(scheduler.resolvePriority(null, null)).isEqualTo(5);
    }

    @Test
    void shouldReturnDefaultPriorityFiveWhenPriorityIsNull() {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        request.setPriority(null);
        assertThat(scheduler.resolvePriority(request, null)).isEqualTo(5);
    }

    @Test
    void shouldClampPriorityToOneWhenBelowRange() {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        request.setPriority(0);
        assertThat(scheduler.resolvePriority(request, null)).isEqualTo(1);

        request.setPriority(-5);
        assertThat(scheduler.resolvePriority(request, null)).isEqualTo(1);
    }

    @Test
    void shouldClampPriorityToNineWhenAboveRange() {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        request.setPriority(10);
        assertThat(scheduler.resolvePriority(request, null)).isEqualTo(9);

        request.setPriority(100);
        assertThat(scheduler.resolvePriority(request, null)).isEqualTo(9);
    }

    @Test
    void shouldReturnExactValueWhenWithinValidRange() {
        ResourceSchedulingRequest request = new ResourceSchedulingRequest();
        for (int p = 1; p <= 9; p++) {
            request.setPriority(p);
            assertThat(scheduler.resolvePriority(request, null)).isEqualTo(p);
        }
    }

    // --- resolvePriorityBand ---

    @Test
    void shouldReturnHighBandForPriorityOneToThree() {
        assertThat(scheduler.resolvePriorityBand(1)).isEqualTo(SchedulingPriorityBand.HIGH.code());
        assertThat(scheduler.resolvePriorityBand(2)).isEqualTo(SchedulingPriorityBand.HIGH.code());
        assertThat(scheduler.resolvePriorityBand(3)).isEqualTo(SchedulingPriorityBand.HIGH.code());
    }

    @Test
    void shouldReturnMediumBandForPriorityFourToSix() {
        assertThat(scheduler.resolvePriorityBand(4)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
        assertThat(scheduler.resolvePriorityBand(5)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
        assertThat(scheduler.resolvePriorityBand(6)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
    }

    @Test
    void shouldReturnLowBandForPrioritySevenToNine() {
        assertThat(scheduler.resolvePriorityBand(7)).isEqualTo(SchedulingPriorityBand.LOW.code());
        assertThat(scheduler.resolvePriorityBand(8)).isEqualTo(SchedulingPriorityBand.LOW.code());
        assertThat(scheduler.resolvePriorityBand(9)).isEqualTo(SchedulingPriorityBand.LOW.code());
    }

    @Test
    void shouldReturnMediumBandWhenPriorityIsNull() {
        assertThat(scheduler.resolvePriorityBand(null)).isEqualTo(SchedulingPriorityBand.MEDIUM.code());
    }
}
