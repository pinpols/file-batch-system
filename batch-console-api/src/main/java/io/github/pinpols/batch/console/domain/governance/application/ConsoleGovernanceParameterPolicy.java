package io.github.pinpols.batch.console.domain.governance.application;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 治理参数的命名空间、默认值和展示说明策略。
 *
 * <p>该策略属于应用层，不属于 HTTP 适配层。Controller 只负责绑定请求并委托本类和参数服务；新增治理参数时只需在此处登记，
 * 避免默认值和白名单散落在多个端点中。
 */
@Component
public class ConsoleGovernanceParameterPolicy {

  private static final String PREFIX = "governance.";

  private static final Map<String, String> DEFAULT_VALUES;

  static {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("governance.outbox.circuit-breaker.failure-threshold", "3");
    values.put("governance.outbox.circuit-breaker.cooldown-millis", "60000");
    values.put("governance.dispatch.circuit-breaker.failure-threshold", "5");
    values.put("governance.dispatch.circuit-breaker.cooldown-millis", "60000");
    values.put("governance.rate-limit.login-ip-per-minute", "10");
    values.put("governance.rate-limit.sensitive-op-user-per-minute", "30");
    values.put("governance.rate-limit.launch-per-tenant-per-minute", "0");
    values.put("governance.rate-limit.release-per-tenant-per-minute", "0");
    DEFAULT_VALUES = Collections.unmodifiableMap(values);
  }

  /** 返回稳定顺序的已知治理参数及默认值。 */
  public Map<String, String> defaultValues() {
    return DEFAULT_VALUES;
  }

  /** 判断参数是否属于治理命名空间；未知的治理参数保留兼容性，允许作为自定义扩展保存。 */
  public boolean isGovernanceKey(String key) {
    return EmptyChecks.isNotBlank(key) && key.startsWith(PREFIX);
  }

  /** 返回审计描述，已知参数使用明确名称，扩展参数统一标为 custom。 */
  public String description(String key) {
    return DEFAULT_VALUES.containsKey(key)
        ? "Governance parameter: " + key
        : "Custom governance parameter";
  }
}
