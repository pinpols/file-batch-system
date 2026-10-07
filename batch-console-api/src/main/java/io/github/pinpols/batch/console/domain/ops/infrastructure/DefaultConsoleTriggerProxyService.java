package io.github.pinpols.batch.console.domain.ops.infrastructure;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.resilience.DownstreamFallback;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleSchedulerCommandResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleTriggerActionResponse;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleTriggerStatusResponse;
import io.github.pinpols.batch.console.application.ops.ConsoleTriggerProxyService;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.query.TenantScopeResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/** Trigger 内部接口代理：读路径统一降级，写路径失败传播，并按当前租户收敛列表。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleTriggerProxyService implements ConsoleTriggerProxyService {

  private static final String SVC = "trigger";
  private static final String TENANT_ID = "tenantId";
  // 固定内部路由属于 API 契约，部署地址由 TriggerInternalRestClient 的配置提供。
  @SuppressWarnings("java:S1075")
  private static final String ACTION_PATH = "/api/triggers/management/{action}";

  private static final ParameterizedTypeReference<CommonResponse<ConsoleSchedulerCommandResponse>>
      SCHEDULER_RESPONSE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<CommonResponse<ConsoleTriggerActionResponse>>
      ACTION_RESPONSE = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<
          CommonResponse<List<ConsoleTriggerStatusResponse>>>
      LIST_RESPONSE = new ParameterizedTypeReference<>() {};

  private final TriggerInternalRestClient triggerInternalRestClient;
  private final TenantScopeResolver tenantGuard;
  private final DownstreamFallback downstreamFallback;

  @Override
  public ConsoleSchedulerCommandResponse schedulerStatus() {
    return downstreamFallback.callOrFallback(
        SVC,
        "scheduler-status",
        () -> {
          CommonResponse<ConsoleSchedulerCommandResponse> response = triggerInternalRestClient
              .client()
              .get()
              .uri("/api/triggers/management/scheduler-status")
              .retrieve()
              .body(SCHEDULER_RESPONSE);
          return EmptyChecks.isNotNull(response)
              ? response.data()
              : new ConsoleSchedulerCommandResponse(null);
        },
        ex -> new ConsoleSchedulerCommandResponse("UNKNOWN"));
  }

  @Override
  public ConsoleSchedulerCommandResponse schedulerPauseAll() {
    return downstreamFallback.callOrThrow(
        SVC, "scheduler-pause-all", () -> schedulerCommand("pause-all"));
  }

  @Override
  public ConsoleSchedulerCommandResponse schedulerResumeAll() {
    return downstreamFallback.callOrThrow(
        SVC, "scheduler-resume-all", () -> schedulerCommand("resume-all"));
  }

  @Override
  public List<ConsoleTriggerStatusResponse> triggerList() {
    String tenantScope = tenantGuard.currentTenantScopeOrNull();
    return downstreamFallback.callOrFallback(
        SVC,
        "list",
        () -> {
          CommonResponse<List<ConsoleTriggerStatusResponse>> response = triggerInternalRestClient
              .client()
              .get()
              .uri("/api/triggers/management/list")
              .retrieve()
              .body(LIST_RESPONSE);
          return filterByTenant(
              EmptyChecks.isNotNull(response) ? response.data() : List.of(), tenantScope);
        },
        ex -> List.of());
  }

  /** 下游不提供租户筛选；缺失租户标识的条目在租户作用域内按失败关闭处理。 */
  static List<ConsoleTriggerStatusResponse> filterByTenant(
      List<ConsoleTriggerStatusResponse> data, String tenantScope) {
    if (EmptyChecks.isNull(data)) {
      return List.of();
    }
    if (EmptyChecks.isNull(tenantScope)) {
      return data;
    }
    return data.stream()
        .filter(item -> EmptyChecks.isNotNull(item) && tenantScope.equals(item.tenantId()))
        .toList();
  }

  @Override
  public ConsoleTriggerActionResponse triggerAction(
      String tenantId, String jobCode, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return downstreamFallback.callOrThrow(SVC, "action", () -> {
      CommonResponse<ConsoleTriggerActionResponse> response = triggerInternalRestClient
          .client()
          .post()
          .uri(uriBuilder -> uriBuilder
              .path(ACTION_PATH)
              .queryParam(TENANT_ID, resolved)
              .queryParam("jobCode", jobCode)
              .build(action))
          .retrieve()
          .body(ACTION_RESPONSE);
      return EmptyChecks.isNotNull(response)
          ? response.data()
          : new ConsoleTriggerActionResponse(null, null, null);
    });
  }

  @Override
  public ConsoleTriggerActionResponse pauseByTenant(String tenantId) {
    return downstreamFallback.callOrThrow(
        SVC, "pause-tenant", () -> tenantCommand("pause-tenant", tenantId));
  }

  @Override
  public ConsoleTriggerActionResponse resumeByTenant(String tenantId) {
    return downstreamFallback.callOrThrow(
        SVC, "resume-tenant", () -> tenantCommand("resume-tenant", tenantId));
  }

  private ConsoleSchedulerCommandResponse schedulerCommand(String action) {
    CommonResponse<ConsoleSchedulerCommandResponse> response = triggerInternalRestClient
        .client()
        .post()
        .uri(ACTION_PATH, action)
        .retrieve()
        .body(SCHEDULER_RESPONSE);
    return EmptyChecks.isNotNull(response)
        ? response.data()
        : new ConsoleSchedulerCommandResponse(null);
  }

  private ConsoleTriggerActionResponse tenantCommand(String action, String tenantId) {
    CommonResponse<ConsoleTriggerActionResponse> response = triggerInternalRestClient
        .client()
        .post()
        .uri(uriBuilder ->
            uriBuilder.path(ACTION_PATH).queryParam(TENANT_ID, tenantId).build(action))
        .retrieve()
        .body(ACTION_RESPONSE);
    return EmptyChecks.isNotNull(response)
        ? response.data()
        : new ConsoleTriggerActionResponse(null, null, null);
  }
}
