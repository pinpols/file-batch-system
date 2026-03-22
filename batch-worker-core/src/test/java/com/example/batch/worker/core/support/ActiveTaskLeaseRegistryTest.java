package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActiveTaskLeaseRegistryTest {

    private ActiveTaskLeaseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ActiveTaskLeaseRegistry();
    }

    @Test
    void shouldRegisterAndSnapshotLease() {
        registry.register("task-1", "tenant-A", "worker-1");

        assertThat(registry.snapshot()).hasSize(1);
        ActiveTaskLeaseRegistry.ActiveTaskLease lease = registry.snapshot().iterator().next();
        assertThat(lease.getTaskId()).isEqualTo("task-1");
        assertThat(lease.getTenantId()).isEqualTo("tenant-A");
        assertThat(lease.getWorkerId()).isEqualTo("worker-1");
    }

    @Test
    void shouldRemoveLease() {
        registry.register("task-1", "tenant-A", "worker-1");
        registry.remove("task-1");

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void shouldIgnoreRegisterWithNullArguments() {
        registry.register(null, "tenant-A", "worker-1");
        registry.register("task-1", null, "worker-1");
        registry.register("task-1", "tenant-A", null);

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void shouldIgnoreRemoveWithNullTaskId() {
        registry.register("task-1", "tenant-A", "worker-1");
        registry.remove(null); // should not throw

        assertThat(registry.snapshot()).hasSize(1);
    }

    @Test
    void shouldSupportMultipleLeases() {
        registry.register("task-1", "t1", "w1");
        registry.register("task-2", "t1", "w2");
        registry.register("task-3", "t2", "w1");

        assertThat(registry.snapshot()).hasSize(3);
    }

    @Test
    void shouldOverwriteExistingLeaseWithSameTaskId() {
        registry.register("task-1", "tenant-A", "worker-1");
        registry.register("task-1", "tenant-A", "worker-2");

        assertThat(registry.snapshot()).hasSize(1);
        assertThat(registry.snapshot().iterator().next().getWorkerId()).isEqualTo("worker-2");
    }

    @Test
    void shouldReturnEmptySnapshotWhenNoLeases() {
        assertThat(registry.snapshot()).isEmpty();
    }
}
