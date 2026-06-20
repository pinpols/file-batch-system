package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.orchestrator.controller.request.AlertEmitRequest;

/** 告警事件发送服务。 负责向外部告警通道（短信、邮件、Webhook 等）推送任务执行异常或阈值超标的告警通知。 实现类须保证发送失败时记录日志并不抛出异常，以免影响主流程。 */
public interface AlertEventService {

  void emit(AlertEmitRequest request);

  /**
   * 对超过 ack-SLA 仍未确认的 OPEN 告警执行一次升级 sweep:逐条把 {@code escalation_tier} +1 并放大可见度。
   *
   * @param slaMinutes 每级静默阈值基数(分钟),第 N 级需静默 {@code slaMinutes*N} 分钟
   * @param maxTier 升级到此 tier 后停止
   * @param batchLimit 单次最多处理条数
   * @return 实际升级的告警条数
   */
  int escalateOverdue(int slaMinutes, int maxTier, int batchLimit);
}
