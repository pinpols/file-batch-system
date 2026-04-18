package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;

/**
 * 任务启动服务。
 * 接收启动请求，完成参数校验、实例创建及首次调度推进，返回包含实例 ID 与初始状态的启动响应。
 * 是 Orchestrator 对外暴露的任务触发入口，调用方包括 Trigger 模块和控制台手动触发。
 */
public interface LaunchService {

  LaunchResponse launch(LaunchRequest request);
}
