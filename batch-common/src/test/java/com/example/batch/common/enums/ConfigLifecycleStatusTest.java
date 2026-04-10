package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigLifecycleStatusTest {

    @Test
    void shouldHaveExpectedCodeValues() {
        assertThat(ConfigLifecycleStatus.DRAFT.code()).isEqualTo("DRAFT");
        assertThat(ConfigLifecycleStatus.PENDING_APPROVAL.code()).isEqualTo("PENDING_APPROVAL");
        assertThat(ConfigLifecycleStatus.PUBLISHED.code()).isEqualTo("PUBLISHED");
        assertThat(ConfigLifecycleStatus.GRAY.code()).isEqualTo("GRAY");
        assertThat(ConfigLifecycleStatus.ROLLED_BACK.code()).isEqualTo("ROLLED_BACK");
    }

    @Test
    void shouldHaveNonBlankLabels() {
        for (ConfigLifecycleStatus status : ConfigLifecycleStatus.values()) {
            assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
        }
    }

    @Test
    void shouldContainFiveValues() {
        assertThat(ConfigLifecycleStatus.values()).hasSize(5);
    }
}
