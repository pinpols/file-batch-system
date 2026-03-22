package com.example.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerRegistryStatusTest {

    @Test
    void shouldHaveCorrectCodeValues() {
        assertThat(WorkerRegistryStatus.ONLINE.code()).isEqualTo("ONLINE");
        assertThat(WorkerRegistryStatus.OFFLINE.code()).isEqualTo("OFFLINE");
        assertThat(WorkerRegistryStatus.DRAINING.code()).isEqualTo("DRAINING");
        assertThat(WorkerRegistryStatus.DECOMMISSIONED.code()).isEqualTo("DECOMMISSIONED");
    }

    @Test
    void shouldHaveNonBlankLabels() {
        for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
            assertThat(status.label()).as("label for %s", status.name()).isNotBlank();
        }
    }

    @Test
    void codeShouldMatchEnumName() {
        for (WorkerRegistryStatus status : WorkerRegistryStatus.values()) {
            assertThat(status.code()).isEqualTo(status.name());
        }
    }

    @Test
    void drainLifecycleOrderShouldBeLogical() {
        // Verifies the lifecycle: ONLINE → DRAINING → DECOMMISSIONED
        WorkerRegistryStatus[] values = WorkerRegistryStatus.values();
        int onlineIdx = indexOf(values, WorkerRegistryStatus.ONLINE);
        int drainingIdx = indexOf(values, WorkerRegistryStatus.DRAINING);
        int decommissionedIdx = indexOf(values, WorkerRegistryStatus.DECOMMISSIONED);

        assertThat(onlineIdx).isLessThan(drainingIdx);
        assertThat(drainingIdx).isLessThan(decommissionedIdx);
    }

    private int indexOf(WorkerRegistryStatus[] values, WorkerRegistryStatus target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return -1;
    }
}
