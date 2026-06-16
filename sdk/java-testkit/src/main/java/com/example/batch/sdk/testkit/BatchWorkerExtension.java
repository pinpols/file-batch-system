package com.example.batch.sdk.testkit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * {@link BatchWorkerTest} 背后的 JUnit5 扩展:每个测试类起一个 {@link FakeBatchPlatform}(beforeAll),
 * 关一个(afterAll),并按参数类型注入到测试方法 / 构造器。
 *
 * <p>broker 启动有秒级开销,所以选类级生命周期(同类多个 {@code @Test} 复用同一假平台);录制容器在每个测试间不自动清空, 测试用不同 taskId 隔离即可。
 */
public final class BatchWorkerExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

  private static final Namespace NAMESPACE = Namespace.create(BatchWorkerExtension.class);
  private static final String KEY = "fake-batch-platform";

  @Override
  public void beforeAll(ExtensionContext context) {
    store(context).put(KEY, FakeBatchPlatform.start());
  }

  @Override
  public void afterAll(ExtensionContext context) {
    FakeBatchPlatform platform = store(context).remove(KEY, FakeBatchPlatform.class);
    if (platform != null) {
      platform.close();
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
    return parameterContext.getParameter().getType() == FakeBatchPlatform.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    return store(context).get(KEY, FakeBatchPlatform.class);
  }

  // 类级 store:beforeAll/afterAll 在类 context 写入;方法级 resolveParameter 经 store 层级向上查到。
  private static Store store(ExtensionContext context) {
    return context.getStore(NAMESPACE);
  }
}
