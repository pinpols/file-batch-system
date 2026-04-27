package com.example.batch.worker.processes.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.core.support.AbstractStepBeanRegistrar;
import com.example.batch.worker.processes.domain.ProcessWorkerType;
import com.example.batch.worker.processes.stage.ProcessStageStep;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Process worker 启动后把所有 {@link ProcessStageStep} bean 登记到 {@code batch.step_registry}，
 * module=PROCESS，供 console-api 上传 Excel 时校验 pipeline impl_code 白名单。
 */
@Component
public class ProcessStepBeanRegistrar extends AbstractStepBeanRegistrar<ProcessStageStep> {

  public ProcessStepBeanRegistrar(
      ApplicationContext applicationContext, StepRegistryMapper stepRegistryMapper) {
    super(
        applicationContext,
        stepRegistryMapper,
        ProcessStageStep.class,
        ProcessWorkerType.PROCESS,
        ProcessStageStep::implCode);
  }
}
