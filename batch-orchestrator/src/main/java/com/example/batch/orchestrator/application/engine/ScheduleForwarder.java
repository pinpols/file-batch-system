package com.example.batch.orchestrator.application.engine;

import com.example.batch.orchestrator.application.plan.SchedulePlan;

/**
 * 调度推进器。
 * 接收一个调度计划并将其向前推进一步，返回推进结果（包含后续动作与状态）。
 * 实现类负责选择执行路径（立即分发、入队等待、跳过等）并输出 {@link ScheduleForwarderResult}。
 */
public interface ScheduleForwarder {
  ScheduleForwarderResult advance(SchedulePlan plan);
}
