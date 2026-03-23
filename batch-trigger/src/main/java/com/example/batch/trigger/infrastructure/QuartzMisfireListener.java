package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.MisfireHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class QuartzMisfireListener implements MisfireHandler {

    @Override
    public void handle(String triggerName) {
        // Misfire 当前只做审计留痕，真正的 catch-up/补偿决策仍由编排层统一处理。
        log.warn("Quartz trigger misfire detected, triggerName={}", triggerName);
    }
}
