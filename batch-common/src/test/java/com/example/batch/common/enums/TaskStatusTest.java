package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    void shouldHaveCorrectCodeValues() {
        assertThat(TaskStatus.CREATED.code()).isEqualTo("CREATED");
        assertThat(TaskStatus.READY.code()).isEqualTo("READY");
        assertThat(TaskStatus.RUNNING.code()).isEqualTo("RUNNING");
        assertThat(TaskStatus.SUCCESS.code()).isEqualTo("SUCCESS");
        assertThat(TaskStatus.FAILED.code()).isEqualTo("FAILED");
        assertThat(TaskStatus.CANCELLED.code()).isEqualTo("CANCELLED");
        assertThat(TaskStatus.TERMINATED.code()).isEqualTo("TERMINATED");
    }

    @Test
    void shouldHaveNonBlankLabels() {
        for (TaskStatus status : TaskStatus.values()) {
            assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
        }
    }

    @Test
    void codeShouldMatchEnumName() {
        for (TaskStatus status : TaskStatus.values()) {
            assertThat(status.code()).isEqualTo(status.name());
        }
    }

    @Test
    void shouldContainSevenValues() {
        assertThat(TaskStatus.values()).hasSize(7);
    }
}
