package com.example.batch.console.infrastructure.ops;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link ConsoleTriggerProxyService} 的默认实现:通过 RestClient 转发请求到触发器管理接口。
 *
 * <p>P2-1(2026-05-16):trigger client 构造下沉到 {@link TriggerInternalRestClient}, 该类用 ObjectProvider
 * 拿独立 builder + 加 5s/30s 超时 + 注入 secret。本类专注路由 + tenant guard。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTriggerProxyService implements ConsoleTriggerProxyService {

  private final TriggerInternalRestClient triggerInternalRestClient;
  private final ConsoleTenantGuard tenantGuard;

  private RestClient newClient() {
    return triggerInternalRestClient.build();
  }

  @Override
  public Map<String, String> schedulerStatus() {
    return proxyScheduler("GET", "/api/triggers/management/scheduler-status");
  }

  @Override
  public Map<String, String> schedulerPauseAll() {
    return proxyScheduler("POST", "/api/triggers/management/pause-all");
  }

  @Override
  public Map<String, String> schedulerResumeAll() {
    return proxyScheduler("POST", "/api/triggers/management/resume-all");
  }

  @Override
  public List<Object> triggerList() {
    // 只读查询,trigger 服务不可达时降级为空 list(FE 渲染空表 + 上层 banner 提示),
    // 不向上抛 500 让整页崩。状态变更(register / pause / resume)仍然 fail-fast。
    try {
      CommonResponse<List<Object>> resp =
          newClient()
              .get()
              .uri("/api/triggers/management/list")
              .retrieve()
              .body(new ParameterizedTypeReference<CommonResponse<List<Object>>>() {});
      return resp != null ? resp.data() : List.of();
    } catch (RestClientException ex) {
      log.warn(
          "trigger downstream unavailable, degrading to empty list: {}", ex.getMessage());
      return List.of();
    }
  }

  @Override
  public Map<String, String> triggerAction(String tenantId, String jobCode, String action) {
    String resolved = tenantGuard.resolveTenant(tenantId);
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
  }

  @Override
  public Map<String, String> pauseByTenant(String tenantId) {
    return proxyScheduler("POST", "/api/triggers/management/pause-tenant?tenantId=" + tenantId);
  }

  @Override
  public Map<String, String> resumeByTenant(String tenantId) {
    return proxyScheduler("POST", "/api/triggers/management/resume-tenant?tenantId=" + tenantId);
  }

  private Map<String, String> proxyScheduler(String method, String path) {
    RestClient client = newClient();
    CommonResponse<Map<String, String>> resp;
    if ("GET".equals(method)) {
      resp =
          client
              .get()
              .uri(path)
              .retrieve()
              .body(new ParameterizedTypeReference<CommonResponse<Map<String, String>>>() {});
    } else {
      resp =
          client
              .post()
              .uri(path)
              .retrieve()
              .body(new ParameterizedTypeReference<CommonResponse<Map<String, String>>>() {});
    }
    return resp != null ? resp.data() : Map.of();
  }
}
