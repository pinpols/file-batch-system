package com.example.batch.sdk.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 标在租户 worker 集成测试类上 —— 自动起 / 关一个 {@link FakeBatchPlatform}(每个测试类一个,跨方法复用以摊薄 broker
 * 启动开销),并把它作为参数注入测试方法 / 构造器。
 *
 * <pre>{@code
 * @BatchWorkerTest
 * class MyImportHandlerIT {
 *   @Test
 *   void importsRows(FakeBatchPlatform platform) {
 *     var client = BatchPlatformClient.builder(platform.configFor("t1", "w1"))
 *         .register(new MyImportHandler())
 *         .build();
 *     client.start();
 *     try {
 *       platform.dispatch(TaskDispatchMessageBuilder.dispatch("my_import").taskId(1L).build());
 *       assertThat(platform.awaitReport(1L, Duration.ofSeconds(5)).success()).isTrue();
 *     } finally {
 *       client.stop();
 *     }
 *   }
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(BatchWorkerExtension.class)
public @interface BatchWorkerTest {}
