package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.MisfireHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Quartz 错失触发（misfire）回调：当 scheduler 因 JVM 停顿、长事务、集群故障等错过了 fire time， 在此仅落一条 warn
 * 日志留痕。追赶（catch-up）与补偿策略集中在编排层——{@code CatchUpPolicyType} 结合 {@code batch_day}
 * 语义决定是跳过、自动补跑还是触发审批，trigger 层不做业务决策。
 */
@Component
@Slf4j
public class QuartzMisfireListener implements MisfireHandler {

  @Override
  public void handle(String triggerName) {
    // 错失触发当前仅做审计留痕，追赶与补偿策略由编排层统一决策。
    log.warn("Quartz trigger misfire detected, triggerName={}", triggerName);
  }
}
