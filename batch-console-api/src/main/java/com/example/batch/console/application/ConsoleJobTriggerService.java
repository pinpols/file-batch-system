package com.example.batch.console.application;

import com.example.batch.console.web.request.job.TriggerRequest;
import java.util.List;
import java.util.Map;

/** 控制台作业触发服务：手工触发、API 触发、批量触发、dry-run 校验。 */
public interface ConsoleJobTriggerService {

  /** 手工/API 触发作业运行（幂等键由请求头传入）。 */
  String trigger(TriggerRequest request, String idempotencyKey);

  /** dryRun 校验：检查 job 是否可触发，不真正执行。 */
  Map<String, Object> dryRunTrigger(TriggerRequest request);

  /** 批量触发：同时触发多个 job。 */
  List<Map<String, Object>> batchTrigger(List<TriggerRequest> items, String idempotencyKey);
}
