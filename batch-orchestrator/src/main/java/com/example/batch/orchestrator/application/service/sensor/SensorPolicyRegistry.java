package com.example.batch.orchestrator.application.service.sensor;

import com.example.batch.common.enums.SensorType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * ADR-028 SensorPolicy 路由表。Spring 注入所有 {@link SensorPolicy} bean，构造期按 {@link SensorPolicy#type()} 建
 * enum map。
 *
 * <p>同一 SensorType 不允许两个 bean，重复直接 fail-fast 防止线上路由歧义。
 */
@Component
public class SensorPolicyRegistry {

  private final Map<SensorType, SensorPolicy> policies = new EnumMap<>(SensorType.class);

  public SensorPolicyRegistry(List<SensorPolicy> policyBeans) {
    for (SensorPolicy policy : policyBeans) {
      SensorPolicy existing = policies.putIfAbsent(policy.type(), policy);
      if (existing != null) {
        throw new IllegalStateException(
            "Duplicate SensorPolicy for type "
                + policy.type()
                + ": "
                + existing.getClass().getName()
                + " vs "
                + policy.getClass().getName());
      }
    }
  }

  /** 路由到 {@code type} 对应 policy，未注册时返回 null。 */
  public SensorPolicy resolve(SensorType type) {
    return policies.get(type);
  }
}
