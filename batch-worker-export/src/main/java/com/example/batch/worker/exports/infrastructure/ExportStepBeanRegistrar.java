package com.example.batch.worker.exports.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.core.support.AbstractStepBeanRegistrar;
import com.example.batch.worker.exports.stage.ExportStageStep;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Export worker 启动后把所有 {@link ExportStageStep} bean 登记到 {@code batch.step_registry}，
 * module=EXPORT，供 console-api 上传 Excel 时校验 pipeline impl_code 白名单。
 */
@Component
public class ExportStepBeanRegistrar extends AbstractStepBeanRegistrar<ExportStageStep> {

  public ExportStepBeanRegistrar(
      ApplicationContext applicationContext,
      StepRegistryMapper stepRegistryMapper,
      PlatformTransactionManager transactionManager) {
    super(
        applicationContext,
        stepRegistryMapper,
        transactionManager,
        ExportStageStep.class,
        "EXPORT",
        ExportStageStep::implCode);
  }
}
