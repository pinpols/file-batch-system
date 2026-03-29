package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.MisfireHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class QuartzMisfireListener implements MisfireHandler {

    @Override
    public void handle(String triggerName) {
        // 错失触发当前仅做审计留痕，追赶与补偿策略由编排层统一决策。
        log.warn("Quartz trigger misfire detected, triggerName={}", triggerName);
    }
}
