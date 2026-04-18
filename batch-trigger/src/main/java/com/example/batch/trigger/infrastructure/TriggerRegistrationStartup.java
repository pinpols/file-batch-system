package com.example.batch.trigger.infrastructure;

import com.example.batch.trigger.domain.TriggerRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后自动执行触发器全量注册的引导组件。
 * 实现 {@link org.springframework.boot.ApplicationRunner}，在 Spring 上下文就绪后调用
 * {@code TriggerRegistrationService#registerAll}，确保所有已启用的触发器在进程重启后
 * 均能正确恢复调度，无需人工干预。
 */
@Component
@RequiredArgsConstructor
public class TriggerRegistrationStartup implements ApplicationRunner {

  private final TriggerRegistrationService triggerRegistrationService;

  @Override
  public void run(ApplicationArguments args) {
    triggerRegistrationService.registerAll();
  }
}
