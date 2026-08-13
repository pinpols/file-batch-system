package io.github.pinpols.batch.orchestrator.application.service.governance;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 补偿流程的显式事务执行器。
 *
 * <p>补偿命令、业务副作用和失败审计属于三个不同提交单元。用模板表达边界，避免这些关键语义依赖同类代理注入。
 */
@Component
public class CompensationTransactionExecutor {

  private final TransactionTemplate required;
  private final TransactionTemplate requiresNew;

  public CompensationTransactionExecutor(PlatformTransactionManager transactionManager) {
    required = new TransactionTemplate(transactionManager);
    requiresNew = new TransactionTemplate(transactionManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  <T> T required(Supplier<T> action) {
    return required.execute(status -> action.get());
  }

  void requiresNew(Runnable action) {
    requiresNew.executeWithoutResult(status -> action.run());
  }
}
