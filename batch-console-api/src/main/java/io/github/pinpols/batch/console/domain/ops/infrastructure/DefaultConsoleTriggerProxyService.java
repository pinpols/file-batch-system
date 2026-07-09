package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleTriggerProxyService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link ConsoleTriggerProxyService} 的默认实现:通过 RestClient 转发请求到触发器管理接口。
 *
 * <p>P2-1(2026-05-16):trigger client 构造下沉到 {@link TriggerInternalRestClient}, 该类用 ObjectProvider
 * 拿独立 builder + 加 5s/30s 超时 + 注入 secret。本类专注路由 + tenant guard。
 *
 * <p>P1-B(2026-05-30):全部调用走 {@link DownstreamFallback} 统一打 metrics。读路径 {@code list} / {@code
 * schedulerStatus} 用 {@code callOrFallback} 降级,其余写路径用 {@code callOrThrow} fail-fast。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTriggerProxyService implements ConsoleTriggerProxyService {

  private static final String SVC = "trigger";

  private final TriggerInternalRestClient triggerInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;
  private final DownstreamFallback downstreamFallback;

  private RestClient newClient() {
    return triggerInternalRestClient.build();
  }

  @Override
  public Map<String, String> schedulerStatus() {
    return downstreamFallback.callOrFallback(
        SVC,
        "scheduler-status",
        () -> proxyGet("/api/triggers/management/scheduler-status"),
        ex -> Map.of("status", "UNKNOWN"));
  }

  @Override
  public Map<String, String> schedulerPauseAll() {
    return downstreamFallback.callOrThrow(
        SVC, "scheduler-pause-all", () -> proxyPost("/api/triggers/management/pause-all"));
  }

  @Override
  public Map<String, String> schedulerResumeAll() {
    return downstreamFallback.callOrThrow(
        SVC, "scheduler-resume-all", () -> proxyPost("/api/triggers/management/resume-all"));
  }

  @Override
  public List<Object> triggerList() {
    // SEC(跨租户越权修复):下游 /api/triggers/management/list 无 tenant 过滤能力(返回全租户
    // TriggerStatusInfo),故在 console 侧按调用方租户收敛:
    //   - 全局角色(ADMIN / AUDITOR)→ scope 为 null,返回全部;
    //   - 租户角色(TENANT_ADMIN / TENANT_USER)→ 仅返回 tenantId 命中自身租户的条目。
    // 只读查询,trigger 不可达 → DownstreamFallback 降级为空 list + metrics(详见
    // docs/runbook/downstream-degradation.md "trigger:list" 条目)。
    // 状态变更(register / pause / resume / triggerAction)仍 fail-fast,见各方法的 callOrThrow。
    String tenantScope = tenantGuard.currentTenantScopeOrNull();
    return downstreamFallback.callOrFallback(
        SVC,
        "list",
        () -> {
          CommonResponse<List<Object>> resp =
              newClient()
                  .get()
                  .uri("/api/triggers/management/list")
                  .retrieve()
                  .body(new ParameterizedTypeReference<CommonResponse<List<Object>>>() {});
          List<Object> data = resp != null ? resp.data() : List.<Object>of();
          return filterByTenant(data, tenantScope);
        },
        ex -> List.<Object>of());
  }

  /**
   * 按租户作用域过滤下游触发器列表。scope 为 null(全局角色)时原样返回; 否则仅保留 {@code tenantId} 命中 scope 的条目(下游条目反序列化为 {@code
   * Map},取其 {@code tenantId} 键)。无法识别 tenantId 的条目按「不属于本租户」丢弃,fail-closed。
   */
  static List<Object> filterByTenant(List<Object> data, String tenantScope) {
    if (tenantScope == null || data == null) {
      return data != null ? data : List.<Object>of();
    }
    return data.stream()
        .filter(
            item ->
                item instanceof Map<?, ?> row
                    && tenantScope.equals(String.valueOf(row.get("tenantId"))))
        .toList();
  }

  @Override
  public Map<String, String> triggerAction(String tenantId, String jobCode, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(
        SVC,
        "action",
        () -> {
          CommonResponse<Map<String, String>> resp =
              newClient()
                  .post()
                  .uri(
                      uriBuilder ->
                          uriBuilder
                              .path("/api/triggers/management/{action}")
                              .queryParam("tenantId", resolved)
                              .queryParam("jobCode", jobCode)
                              .build(action))
                  .retrieve()
                  .body(new ParameterizedTypeReference<CommonResponse<Map<String, String>>>() {});
          return resp != null ? resp.data() : Map.of();
        });
  }

  @Override
  public Map<String, String> pauseByTenant(String tenantId) {
    return downstreamFallback.callOrThrow(
        SVC,
        "pause-tenant",
        () -> proxyPost("/api/triggers/management/pause-tenant?tenantId=" + tenantId));
  }

  @Override
  public Map<String, String> resumeByTenant(String tenantId) {
    return downstreamFallback.callOrThrow(
        SVC,
        "resume-tenant",
        () -> proxyPost("/api/triggers/management/resume-tenant?tenantId=" + tenantId));
  }

  private Map<String, String> proxyGet(String path) {
    CommonResponse<Map<String, String>> resp =
        newClient()
            .get()
            .uri(path)
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<Map<String, String>>>() {});
    return resp != null ? resp.data() : Map.of();
  }

  private Map<String, String> proxyPost(String path) {
    CommonResponse<Map<String, String>> resp =
        newClient()
            .post()
            .uri(path)
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<Map<String, String>>>() {});
    return resp != null ? resp.data() : Map.of();
  }
}
