package com.example.batch.trigger.domain;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;

/**
 * Orchestrator 触发适配器接口，定义将 {@link com.example.batch.common.dto.LaunchRequest}
 * 发送至 Orchestrator 服务的契约。
 * 实现类负责具体的传输协议（如 HTTP）、序列化格式及错误处理；调用方不应关心底层传输细节。
 */
public interface OrchestratorTriggerAdapter {

  LaunchResponse sendTrigger(LaunchRequest request);
}
