package com.example.batch.worker.core.support;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class ActiveTaskLeaseRegistry {

    private final Map<String, ActiveTaskLease> activeTaskLeases = new ConcurrentHashMap<>();

    public void register(String taskId, String tenantId, String workerId) {
        if (taskId == null || tenantId == null || workerId == null) {
            return;
        }
        activeTaskLeases.put(taskId, new ActiveTaskLease(taskId, tenantId, workerId));
    }

    public void remove(String taskId) {
        if (taskId == null) {
            return;
        }
        activeTaskLeases.remove(taskId);
    }

    public Collection<ActiveTaskLease> snapshot() {
        return activeTaskLeases.values();
    }

    @Getter
    public static class ActiveTaskLease {

        private final String taskId;
        private final String tenantId;
        private final String workerId;

        public ActiveTaskLease(String taskId, String tenantId, String workerId) {
            this.taskId = taskId;
            this.tenantId = tenantId;
            this.workerId = workerId;
        }
    }
}
