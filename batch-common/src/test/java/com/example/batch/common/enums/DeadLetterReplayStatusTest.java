package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeadLetterReplayStatusTest {

    @Test
    void shouldHaveCorrectCodeValues() {
        assertThat(DeadLetterReplayStatus.NEW.code()).isEqualTo("NEW");
        assertThat(DeadLetterReplayStatus.REPLAYING.code()).isEqualTo("REPLAYING");
        assertThat(DeadLetterReplayStatus.SUCCESS.code()).isEqualTo("SUCCESS");
        assertThat(DeadLetterReplayStatus.FAILED.code()).isEqualTo("FAILED");
        assertThat(DeadLetterReplayStatus.GIVE_UP.code()).isEqualTo("GIVE_UP");
    }

    @Test
    void shouldHaveNonBlankLabels() {
        for (DeadLetterReplayStatus status : DeadLetterReplayStatus.values()) {
            assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
        }
    }

    @Test
    void codeShouldMatchEnumName() {
        for (DeadLetterReplayStatus status : DeadLetterReplayStatus.values()) {
            assertThat(status.code()).isEqualTo(status.name());
        }
    }

    @Test
    void shouldHaveReplayableInitialStatuses() {
        // NEW and FAILED are replayable initial states
        assertThat(DeadLetterReplayStatus.NEW.code()).isEqualTo("NEW");
        assertThat(DeadLetterReplayStatus.FAILED.code()).isEqualTo("FAILED");
    }

    @Test
    void shouldContainFiveValues() {
        assertThat(DeadLetterReplayStatus.values()).hasSize(5);
    }
}
