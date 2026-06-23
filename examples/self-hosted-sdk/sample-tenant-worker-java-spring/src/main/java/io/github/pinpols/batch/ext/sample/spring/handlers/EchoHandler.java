package io.github.pinpols.batch.ext.sample.spring.handlers;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示范 handler:把 parameters 原样返回。
 *
 * <p>声明成 {@code @Component} 即可 —— starter 会自动发现并 register,无需手写 {@code .register(...)}。
 */
@Component
public class EchoHandler implements SdkTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(EchoHandler.class);

  @Override
  public String taskType() {
    return "echo";
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    log.info("echo handler taskId={} params={}", ctx.taskId(), ctx.parameters());
    return SdkTaskResult.ok("echoed", Map.copyOf(ctx.parameters()));
  }
}
