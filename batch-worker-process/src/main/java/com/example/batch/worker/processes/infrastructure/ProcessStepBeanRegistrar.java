package com.example.batch.worker.processes.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.processes.domain.ProcessWorkerType;
import com.example.batch.worker.processes.stage.ProcessComputePlugin;
import com.example.batch.worker.processes.stage.ProcessStageStep;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Process worker 启动后把 stage step 与 compute plugin 登记到 {@code batch.step_registry}，供 console-api 上传
 * Excel 时校验 pipeline impl_code 白名单。
 */
@Slf4j
@Component
public class ProcessStepBeanRegistrar {

  private final ApplicationContext applicationContext;
  private final StepRegistryMapper stepRegistryMapper;

  public ProcessStepBeanRegistrar(
      ApplicationContext applicationContext, StepRegistryMapper stepRegistryMapper) {
    this.applicationContext = applicationContext;
    this.stepRegistryMapper = stepRegistryMapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void registerStepBeansOnStartup() {
    String module = ProcessWorkerType.PROCESS;
    try {
      Map<String, ProcessStageStep> stageSteps =
          applicationContext.getBeansOfType(ProcessStageStep.class);
      Map<String, ProcessComputePlugin> computePlugins =
          applicationContext.getBeansOfType(ProcessComputePlugin.class);
      stepRegistryMapper.deleteByModule(module);

      Set<String> registeredCodes = new LinkedHashSet<>();
      stageSteps.values().forEach(step -> register(module, step.implCode(), step, registeredCodes));
      computePlugins
          .values()
          .forEach(plugin -> register(module, plugin.implCode(), plugin, registeredCodes));

      log.info(
          "step registry snapshot refreshed: module={}, count={}, implCodes={}",
          module,
          registeredCodes.size(),
          registeredCodes);
    } catch (Exception ex) {
      log.error("step registry snapshot failed: module={}, err={}", module, ex.getMessage(), ex);
    }
  }

  private void register(String module, String implCode, Object bean, Set<String> registeredCodes) {
    if (implCode == null || implCode.isBlank()) {
      return;
    }
    if (registeredCodes.add(implCode)) {
      stepRegistryMapper.insertEntry(module, implCode, bean.getClass().getName());
    }
  }
}
