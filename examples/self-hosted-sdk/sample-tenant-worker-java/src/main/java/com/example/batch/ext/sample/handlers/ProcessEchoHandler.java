package com.example.batch.ext.sample.handlers;

import com.example.batch.sdk.handler.SdkAbstractProcessHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-036 Process 模板 sample —— tenant → tenant transform demo。模拟"读 3 行 → 大写转换 → 写回"。
 *
 * <p>真业务:selectInput 从租户表 select,transform 跑业务计算(返 null = skip),upsert 批量写回租户表。
 */
public class ProcessEchoHandler extends SdkAbstractProcessHandler<String, String> {

  private static final Logger log = LoggerFactory.getLogger(ProcessEchoHandler.class);

  @Override
  public String taskType() {
    return "sample_process_echo";
  }

  @Override
  protected Stream<String> selectInput(SdkTaskContext ctx) {
    log.info("process sample: selectInput for tenant={}", ctx.tenantId());
    // 真业务:jdbcTemplate.queryForStream(...),模板会 try-with-resources 关掉 ResultSet
    return Stream.of("alpha", "", "gamma");
  }

  @Override
  protected String transform(SdkTaskContext ctx, String input) {
    // 空串 skip(返 null),其余转大写
    if (input == null || input.isBlank()) {
      return null;
    }
    return input.toUpperCase(Locale.ROOT);
  }

  @Override
  protected void upsert(SdkTaskContext ctx, List<String> batch) {
    log.info("process sample: upsert size={} rows={}", batch.size(), batch);
  }
}
