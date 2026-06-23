package io.github.pinpols.batch.worker.processes.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.core.mapper.StepRegistryMapper;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.stage.ProcessComputePlugin;
import io.github.pinpols.batch.worker.processes.stage.ProcessStageStep;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ProcessStepBeanRegistrarTest {

  @Test
  void registerStepBeansOnStartup_registersStageStepsAndComputePlugins() {
    ApplicationContext applicationContext = mock(ApplicationContext.class);
    StepRegistryMapper mapper = mock(StepRegistryMapper.class);
    ProcessStageStep stageStep =
        new ProcessStageStep() {
          @Override
          public ProcessStage stage() {
            return ProcessStage.COMPUTE;
          }

          @Override
          public String implCode() {
            return "PROCESS_COMPUTE";
          }

          @Override
          public ProcessStageResult execute(
              io.github.pinpols.batch.worker.processes.domain.ProcessJobContext context) {
            return ProcessStageResult.success(ProcessStage.COMPUTE);
          }
        };
    ProcessComputePlugin plugin =
        new ProcessComputePlugin() {
          @Override
          public String implCode() {
            return "sqlTransformCompute";
          }

          @Override
          public ProcessStageResult compute(
              io.github.pinpols.batch.worker.processes.domain.ProcessJobContext context) {
            return ProcessStageResult.success(ProcessStage.COMPUTE);
          }
        };

    when(applicationContext.getBeansOfType(ProcessStageStep.class))
        .thenReturn(Map.of("computeStep", stageStep));
    when(applicationContext.getBeansOfType(ProcessComputePlugin.class))
        .thenReturn(Map.of("sqlTransformComputePlugin", plugin));

    PlatformTransactionManager txManager =
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(
              org.springframework.transaction.TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        };

    new ProcessStepBeanRegistrar(applicationContext, mapper, txManager)
        .registerStepBeansOnStartup();

    verify(mapper).deleteByModule("PROCESS");
    verify(mapper).insertEntry("PROCESS", "PROCESS_COMPUTE", stageStep.getClass().getName());
    verify(mapper).insertEntry("PROCESS", "sqlTransformCompute", plugin.getClass().getName());
  }
}
