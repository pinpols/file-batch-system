package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import com.example.batch.orchestrator.repository.TenantSchedulerSnapshotRepository;
import com.example.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/scheduler")
@RequiredArgsConstructor
public class SchedulerSnapshotController {

    private final TenantSchedulerSnapshotService tenantSchedulerSnapshotService;
    private final TenantSchedulerSnapshotRepository tenantSchedulerSnapshotRepository;

    @GetMapping("/snapshot")
    public SchedulerSnapshotResponse snapshot(@RequestParam("tenantId") String tenantId) {
        return tenantSchedulerSnapshotService.buildLive(tenantId);
    }

    @GetMapping("/snapshot/history")
    public List<TenantSchedulerSnapshotRecord> history(@RequestParam("tenantId") String tenantId,
                                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return tenantSchedulerSnapshotRepository.listRecent(tenantId, Math.min(Math.max(limit, 1), 100));
    }
}
