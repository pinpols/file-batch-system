package com.example.batch.console.application;

import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TriggerRequest;

/**
 * 控制台作业运维应用服务：触发、补偿、重跑、死信回放、Catch-Up 审批等写操作，经 HTTP 调用编排器与触发器。
 */
public interface ConsoleJobApplicationService {

    /** 手工/API 触发作业运行（幂等键由请求头传入）。 */
    String trigger(TriggerRequest request, String idempotencyKey);

    /** 登记补偿类指令。 */
    String compensation(CompensationCommandRequest request, String idempotencyKey);

    /** 执行补偿动作。 */
    String compensate(CompensateRequest request, String idempotencyKey);

    /** 重跑指定作业实例或分区。 */
    String rerun(RerunRequest request, String idempotencyKey);

    /** 重放死信任务。 */
    String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey);

    /** 审批通过待处理的 Catch-Up 请求。 */
    String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey);
}
