package com.example.batch.sdk.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * ADR-036 — 行级处理计数器,4 个长任务模板(Import/Export/Process/Dispatch)统计 success/skipped/failed/reject, 通过
 * {@link #toOutput()} 写进 {@link com.example.batch.sdk.task.SdkTaskResult#output()},平台 REPORT 透传。
 *
 * <p>线程安全({@link LongAdder}),允许 handler 内多线程并发累加。
 */
public final class SdkRowResult {

  private final LongAdder success = new LongAdder();
  private final LongAdder skipped = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder reject = new LongAdder();

  public void incSuccess() {
    success.increment();
  }

  public void incSkipped() {
    skipped.increment();
  }

  public void incFailed() {
    failed.increment();
  }

  public void incReject() {
    reject.increment();
  }

  public void addSuccess(long n) {
    success.add(n);
  }

  public long success() {
    return success.sum();
  }

  public long skipped() {
    return skipped.sum();
  }

  public long failed() {
    return failed.sum();
  }

  public long reject() {
    return reject.sum();
  }

  /** 处理总数 = success + skipped + failed + reject。 */
  public long total() {
    return success() + skipped() + failed() + reject();
  }

  /** 转 SdkTaskResult.output Map(只放非零项,保持 output 精简)。 */
  public Map<String, Object> toOutput() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("success", success());
    if (skipped() > 0) m.put("skipped", skipped());
    if (failed() > 0) m.put("failed", failed());
    if (reject() > 0) m.put("reject", reject());
    m.put("total", total());
    return m;
  }
}
