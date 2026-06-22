package com.example.batch.worker.processes.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.core.support.AbstractStepBeanRegistrar;
import com.example.batch.worker.processes.domain.ProcessWorkerType;
import com.example.batch.worker.processes.stage.ProcessComputePlugin;
import com.example.batch.worker.processes.stage.ProcessStageStep;
import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Process worker 启动后把 stage step 与 compute plugin 登记到 {@code batch.step_registry}，供 console-api 上传
 * Excel 时校验 pipeline impl_code 白名单。
 *
 * <p>process 链路同 module 下有两种 bean 类型（{@link ProcessStageStep} + {@link ProcessComputePlugin}），通过
 * {@link AbstractStepBeanRegistrar} 的多类型 binding 构造器统一登记，二者共享同一 impl_code 去重集。
 */
@Component
public class ProcessStepBeanRegistrar extends AbstractStepBeanRegistrar<ProcessStageStep> {

  public ProcessStepBeanRegistrar(
      ApplicationContext applicationContext,
      StepRegistryMapper stepRegistryMapper,
      PlatformTransactionManager transactionManager) {
    super(
        applicationContext,
        stepRegistryMapper,
        transactionManager,
        ProcessWorkerType.PROCESS,
        List.of(
            new BeanTypeBinding<>(ProcessStageStep.class, ProcessStageStep::implCode),
            new BeanTypeBinding<>(ProcessComputePlugin.class, ProcessComputePlugin::implCode)));
  }
}
