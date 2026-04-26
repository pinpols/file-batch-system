package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import org.springframework.stereotype.Component;

/**
 * {@link StepExecutionAdapter} 的无操作（no-op）默认实现：直接返回成功响应，不执行任何业务逻辑。
 *
 * <p>仅在 {@code batch-worker-core} 独立运行（未挂载业务 pipeline 模块）时生效。 {@code batch-worker-import} 等子模块会以
 * {@code @Primary} 注解注册各自的适配器覆盖本实现。
 */
@Component
public class DefaultStepExecutionAdapter implements StepExecutionAdapter {

  @Override
  public StepExecutionResponse execute(StepExecutionRequest request) {
    return StepExecutionResponse.successResponse();
  }
}
