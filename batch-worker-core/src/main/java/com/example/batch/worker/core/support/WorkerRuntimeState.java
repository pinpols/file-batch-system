package com.example.batch.worker.core.support;

import com.example.batch.worker.core.domain.WorkerRegistration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class WorkerRuntimeState {

    private final Map<String, WorkerRegistration> registrations = new ConcurrentHashMap<>();

    public void put(WorkerRegistration registration) {
        registrations.put(registration.getWorkerId(), registration);
    }

    public WorkerRegistration get(String workerId) {
        return registrations.get(workerId);
    }

    public WorkerRegistration remove(String workerId) {
        return registrations.remove(workerId);
    }
}
