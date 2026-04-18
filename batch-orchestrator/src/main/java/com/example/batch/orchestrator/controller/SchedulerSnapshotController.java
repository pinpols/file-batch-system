package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.scheduler.TenantSchedulerSnapshotService;
import com.example.batch.orchestrator.controller.response.SchedulerSnapshotResponse;
import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调度器快照内部查询控制器，基础路径 {@code /internal/scheduler}。
 * 提供两个只读端点：{@code GET /snapshot} 获取指定租户的实时调度快照，
 * {@code GET /snapshot/history} 查询历史快照列表（支持 limit 参数）。
 * 仅限内部运维或监控系统调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/scheduler")
@RequiredArgsConstructor
public class SchedulerSnapshotController {

  private final TenantSchedulerSnapshotService tenantSchedulerSnapshotService;

  @GetMapping("/snapshot")
  public SchedulerSnapshotResponse snapshot(@RequestParam("tenantId") String tenantId) {
    return tenantSchedulerSnapshotService.buildLive(tenantId);
  }

  @GetMapping("/snapshot/history")
  public List<TenantSchedulerSnapshotRecord> history(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return tenantSchedulerSnapshotService.history(tenantId, limit);
  }
}
