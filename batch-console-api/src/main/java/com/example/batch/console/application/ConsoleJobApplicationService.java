package com.example.batch.console.application;

import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TriggerRequest;

public interface ConsoleJobApplicationService {

    String trigger(TriggerRequest request, String idempotencyKey);

    String compensate(CompensateRequest request, String idempotencyKey);

    String rerun(RerunRequest request, String idempotencyKey);

    String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey);

    String approveCatchUp(CatchUpApprovalRequest request, String idempotencyKey);
}
