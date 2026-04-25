package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.infrastructure.ConsoleConfigCacheInvalidationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.Idempotent;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置缓存运维（Ops-only）：手动失效 orchestrator 端的 Redis 配置缓存。
 *
 * <p>正常路径：console 通过既有写接口改 job_definition / workflow_definition 等配置时，
 * {@link ConsoleConfigCacheInvalidationService} 会在事务 afterCommit 自动 evict 对应 Redis key，
 * 多个 orchestrator 实例下次读时即刻 cache miss 重新加载。
 *
 * <p>需要手动 evict 的场景：运维直接通过 {@code psql} / SQL migration 改 DB 而**没有走** console
 * 写路径（console 拦截不到）。在不重启 orchestrator 的前提下，这条路径可以让缓存立即失效。
 * 否则需要等 {@code OrchestratorConfigCacheService.CONFIG_CACHE_TTL = 5min} 过期。
 *
 * <p>路径 {@code /api/console/ops/cache/evict-*}，仅 ROLE_ADMIN 可调。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/cache")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleConfigCacheController {

  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;
  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/evict-job-definition")
  public CommonResponse<Map<String, String>> evictJobDefinition(
      @RequestParam("tenantId") String tenantId, @RequestParam("jobCode") String jobCode) {
    cacheInvalidationService.evictJobDefinition(tenantId, jobCode);
    return responseFactory.success(Map.of("evicted", "job-definition:" + tenantId + ":" + jobCode));
  }

  @PostMapping("/evict-all-job-definitions")
  public CommonResponse<Map<String, String>> evictAllJobDefinitions(
      @RequestParam("tenantId") String tenantId) {
    cacheInvalidationService.evictAllJobDefinitions(tenantId);
    return responseFactory.success(Map.of("evicted", "job-definition:" + tenantId + ":*"));
  }

  @PostMapping("/evict-workflow-definition")
  public CommonResponse<Map<String, String>> evictWorkflowDefinition(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("workflowCode") String workflowCode) {
    cacheInvalidationService.evictWorkflowDefinition(tenantId, workflowCode);
    return responseFactory.success(
        Map.of("evicted", "workflow-definition:" + tenantId + ":" + workflowCode));
  }

  @PostMapping("/evict-business-calendar")
  public CommonResponse<Map<String, String>> evictBusinessCalendar(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("calendarCode") String calendarCode) {
    cacheInvalidationService.evictBusinessCalendar(tenantId, calendarCode);
    return responseFactory.success(
        Map.of("evicted", "business-calendar:" + tenantId + ":" + calendarCode));
  }

  @PostMapping("/evict-batch-window")
  public CommonResponse<Map<String, String>> evictBatchWindow(
      @RequestParam("tenantId") String tenantId, @RequestParam("windowCode") String windowCode) {
    cacheInvalidationService.evictBatchWindow(tenantId, windowCode);
    return responseFactory.success(
        Map.of("evicted", "batch-window:" + tenantId + ":" + windowCode));
  }

  @PostMapping("/evict-quota-policies")
  public CommonResponse<Map<String, String>> evictQuotaPolicies(
      @RequestParam("tenantId") String tenantId) {
    cacheInvalidationService.evictQuotaPolicies(tenantId);
    return responseFactory.success(
        Map.of("evicted", "tenant-quota-policy:" + tenantId + ":enabled-first"));
  }
}
