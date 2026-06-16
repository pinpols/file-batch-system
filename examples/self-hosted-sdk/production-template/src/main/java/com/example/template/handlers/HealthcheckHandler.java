package com.example.template.handlers;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 示范 handler 2 / 2 — Healthcheck:返回 worker 内部状态(时间戳 + buildId)。
 *
 * <p>用途:平台侧用 {@code task_type=template.healthcheck} dispatch 一条空 task 来验证
 * "worker 真的拿到 task 并能 report" 而不只是看 heartbeat。
 */
@Component
public class HealthcheckHandler implements SdkTaskHandler {

  private static final Logger log = LoggerFactory.getLogger(HealthcheckHandler.class);

  @Override
  public String taskType() {
    return "template.healthcheck";
  }

  @Override
  public SdkTaskResult execute(SdkTaskContext ctx) {
    log.info("healthcheck taskId={} workerId={}", ctx.taskId(), ctx.workerId());
    return SdkTaskResult.ok(
        "ok",
        Map.of(
            "ts", Instant.now().toString(),
            "workerId", String.valueOf(ctx.workerId()),
            "buildId", String.valueOf(System.getenv().getOrDefault("BATCH_BUILD_ID", "dev"))));
  }
}
