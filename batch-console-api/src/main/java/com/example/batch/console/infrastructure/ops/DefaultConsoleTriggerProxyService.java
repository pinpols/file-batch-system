package com.example.batch.console.infrastructure.ops;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ops.ConsoleTriggerProxyService;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** {@link ConsoleTriggerProxyService} 的默认实现：通过 RestClient 转发请求到触发器管理接口。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleTriggerProxyService implements ConsoleTriggerProxyService {

  /** batch-trigger / batch-orchestrator 的 /api/triggers/management/** 接口要求的 shared-secret 头名。 */
  private static final String INTERNAL_SECRET_HEADER = "X-Internal-Secret";

  private final ConsoleTriggerClientProperties triggerClientProperties;
  private final RestClient.Builder restClientBuilder;
  private final ConsoleTenantGuard tenantGuard;
  private final Environment environment;
  private final BatchSecurityProperties securityProperties;

  /** 构造带 X-Internal-Secret 头的 RestClient。 缺失此头，batch-trigger 侧 InternalSecretFilter 会直接 401。 */
  private RestClient newClient() {
    return restClientBuilder
        .baseUrl(resolveUrl(triggerClientProperties.getBaseUrl()))
        .defaultHeader(INTERNAL_SECRET_HEADER, securityProperties.getInternalSecret())
        .build();
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
    CommonResponse<List<Object>> resp =
        newClient()
            .get()
            .uri("/api/triggers/management/list")
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<List<Object>>>() {});
    return resp != null ? resp.data() : List.of();
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

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }
}
