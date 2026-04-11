package com.example.batch.orchestrator.infrastructure.router;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRoutingPolicy;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class DefaultWorkerRoutingPolicy implements WorkerRoutingPolicy {

    @Override
    public WorkerRouteModel select(List<WorkerRouteModel> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getAvailable()))
                .max(
                        Comparator.comparingInt(
                                candidate ->
                                        candidate.getPriority() == null
                                                ? 0
                                                : candidate.getPriority()))
                .orElse(candidates.get(0));
    }
}
