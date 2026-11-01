package io.github.pinpols.batch.console.domain.governance.application;

import io.github.pinpols.batch.console.application.observability.ConsoleSystemParameterService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 治理参数用例：聚合默认值、应用命名空间策略并委托参数持久化服务。 */
@Service
@RequiredArgsConstructor
public class ConsoleGovernanceParameterService {

  private final ConsoleSystemParameterService parameterService;
  private final ConsoleGovernanceParameterPolicy governancePolicy;

  public Map<String, String> list(String tenantId) {
    Map<String, String> effectiveValues = new LinkedHashMap<>();
    governancePolicy.defaultValues().forEach((key, defaultValue) -> {
      String value = parameterService.getValue(tenantId, key).orElse(defaultValue);
      effectiveValues.put(key, value);
    });
    return effectiveValues;
  }

  public void update(String tenantId, String key, String value, String operator) {
    if (!governancePolicy.isGovernanceKey(key)) {
      return;
    }
    parameterService.upsert(tenantId, key, value, governancePolicy.description(key), operator);
  }

  public void reset(String tenantId, String key) {
    parameterService.delete(tenantId, key);
  }
}
