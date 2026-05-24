package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.core.mapper.StepRegistryMapper;
import com.example.batch.worker.core.support.AbstractStepBeanRegistrar;
import com.example.batch.worker.imports.stage.ImportStageStep;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Import worker 启动后把所有 {@link ImportStageStep} bean 登记到 {@code batch.step_registry}，
 * module=IMPORT，供 console-api 上传 Excel 时校验 pipeline impl_code 白名单。
 */
@Component
public class ImportStepBeanRegistrar extends AbstractStepBeanRegistrar<ImportStageStep> {

  public ImportStepBeanRegistrar(
      ApplicationContext applicationContext,
      StepRegistryMapper stepRegistryMapper,
      PlatformTransactionManager transactionManager) {
    super(
        applicationContext,
        stepRegistryMapper,
        transactionManager,
        ImportStageStep.class,
        "IMPORT",
        ImportStageStep::implCode);
  }
}
