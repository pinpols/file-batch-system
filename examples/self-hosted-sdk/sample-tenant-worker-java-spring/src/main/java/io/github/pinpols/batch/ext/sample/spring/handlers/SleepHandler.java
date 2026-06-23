package io.github.pinpols.batch.ext.sample.spring.handlers;

import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示范 handler:按参数 {@code millis} sleep,演示长任务 + lease 续约场景。
 *
 * <p>同样只是 {@code @Component},starter 自动注册。
 */
@Component
public class SleepHandler implements SdkTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(SleepHandler.class);

  @Override
  public String taskType() {
    return "sleep";
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    Object m = ctx.parameters().get("millis");
    long millis = m instanceof Number n ? n.longValue() : 1000L;
    log.info("sleep handler taskId={} sleeping {}ms", ctx.taskId(), millis);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return SdkTaskResult.fail("interrupted");
    }
    return SdkTaskResult.ok("slept " + millis + "ms", Map.of("millis", millis));
  }
}
