package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.controller.request.AlertEmitRequest;

/** 告警事件发送服务。 负责向外部告警通道（短信、邮件、Webhook 等）推送任务执行异常或阈值超标的告警通知。 实现类须保证发送失败时记录日志并不抛出异常，以免影响主流程。 */
public interface AlertEventService {

  void emit(AlertEmitRequest request);
}
