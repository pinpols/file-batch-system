package com.example.batch.worker.dispatchs.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.core.support.AbstractStepBeanRegistrar;
import com.example.batch.worker.dispatchs.stage.DispatchStageStep;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dispatch worker 启动后把所有 {@link DispatchStageStep} bean 登记到 {@code batch.step_registry}，
 * module=DISPATCH，供 console-api 上传 Excel 时校验 pipeline impl_code 白名单。
 */
@Component
public class DispatchStepBeanRegistrar extends AbstractStepBeanRegistrar<DispatchStageStep> {

  public DispatchStepBeanRegistrar(
      ApplicationContext applicationContext,
      StepRegistryMapper stepRegistryMapper,
      PlatformTransactionManager transactionManager) {
    super(
        applicationContext,
        stepRegistryMapper,
        transactionManager,
        DispatchStageStep.class,
        "DISPATCH",
        DispatchStageStep::implCode);
  }
}
