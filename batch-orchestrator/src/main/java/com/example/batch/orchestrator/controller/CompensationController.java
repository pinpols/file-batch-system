package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.CompensationService;
import com.example.batch.orchestrator.domain.command.CompensationSubmitCommand;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/compensations")
@RequiredArgsConstructor
public class CompensationController {

    private final CompensationService compensationService;

    @PostMapping
    public CompensationResponse submit(@RequestBody CompensationRequest request) {
        return new CompensationResponse(compensationService.submit(new CompensationSubmitCommand(
                request.tenantId(),
                request.compensationType(),
                request.targetId(),
                request.targetInstanceNo(),
                request.jobCode(),
                request.bizDate(),
                request.batchNo(),
                request.relatedFileId(),
                request.channelCode(),
                request.reason(),
                request.operatorId(),
                request.approvalId(),
                request.strategy(),
                request.traceId()
        )));
    }

    public record CompensationRequest(String tenantId,
                                      String compensationType,
                                      Long targetId,
                                      String targetInstanceNo,
                                      String jobCode,
                                      LocalDate bizDate,
                                      String batchNo,
                                      Long relatedFileId,
                                      String channelCode,
                                      String reason,
                                      String operatorId,
                                      String approvalId,
                                      String strategy,
                                      String traceId) {
    }

    public record CompensationResponse(String commandNo) {
    }
}
