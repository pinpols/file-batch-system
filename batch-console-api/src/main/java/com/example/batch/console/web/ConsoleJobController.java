package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.support.ConsoleResponseFactory;
import com.example.batch.console.web.request.CatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/jobs")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleJobController {

    private final ConsoleJobApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/trigger")
    public CommonResponse<String> trigger(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                          @Valid @RequestBody TriggerRequest request) {
        return responseFactory.success(applicationService.trigger(request, idempotencyKey));
    }

    @PostMapping("/compensations")
    public CommonResponse<String> compensation(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                               @Valid @RequestBody CompensationCommandRequest request) {
        return responseFactory.success(applicationService.compensation(request, idempotencyKey));
    }

    @PostMapping("/compensate")
    public CommonResponse<String> compensate(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                             @Valid @RequestBody CompensateRequest request) {
        return responseFactory.success(applicationService.compensate(request, idempotencyKey));
    }

    @PostMapping("/rerun")
    public CommonResponse<String> rerun(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                        @Valid @RequestBody RerunRequest request) {
        return responseFactory.success(applicationService.rerun(request, idempotencyKey));
    }

    @PostMapping("/dead-letters/replay")
    public CommonResponse<String> replayDeadLetter(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                   @Valid @RequestBody DeadLetterReplayRequest request) {
        return responseFactory.success(applicationService.replayDeadLetter(request, idempotencyKey));
    }

    @PostMapping("/catch-up/approve")
    public CommonResponse<String> approveCatchUp(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                 @Valid @RequestBody CatchUpApprovalRequest request) {
        return responseFactory.success(applicationService.approveCatchUp(request, idempotencyKey));
    }
}
