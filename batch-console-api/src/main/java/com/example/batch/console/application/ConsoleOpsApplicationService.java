package com.example.batch.console.application;

import com.example.batch.console.web.response.ops.ConsoleOpsSummaryResponse;

/** 控制台运维总览应用服务：按租户聚合关键运行指标摘要。 */
public interface ConsoleOpsApplicationService {

  /** 返回指定租户的运维摘要（作业、文件、告警等汇总）。 */
  ConsoleOpsSummaryResponse summary(String tenantId);
}
