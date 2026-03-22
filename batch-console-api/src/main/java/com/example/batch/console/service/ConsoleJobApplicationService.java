package com.example.batch.console.service;

import com.example.batch.console.domain.request.CompensateRequest;
import com.example.batch.console.domain.request.CompensationCommandRequest;
import com.example.batch.console.domain.request.CatchUpApprovalRequest;
import com.example.batch.console.domain.request.DeadLetterReplayRequest;
import com.example.batch.console.domain.request.RerunRequest;
import com.example.batch.console.domain.request.TriggerRequest;

public interface ConsoleJobApplicationService {

    String trigger(TriggerRequest request, String idempotencyKey);

    String compensation(CompensationCommandRequest request, String idempotencyKey);

    String compensate(CompensateRequest request, String idempotencyKey);

    String rerun(RerunRequest request, String idempotencyKey);

    String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey);

    String approveCatchUp(CatchUpApprovalRequest request, String idempotencyKey);
}
