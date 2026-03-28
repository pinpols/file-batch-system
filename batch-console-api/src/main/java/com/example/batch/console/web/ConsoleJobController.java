package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台作业运维 REST：触发、补偿、重跑、死信回放、Catch-Up 审批（需管理员角色）。
 */
@RestController
@Validated
@RequestMapping("/api/console/jobs")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleJobController {

    private final ConsoleJobApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    /** 手工触发作业运行。 */
    @PostMapping("/trigger")
    public CommonResponse<String> trigger(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                          @Valid @RequestBody TriggerRequest request) {
        return responseFactory.success(applicationService.trigger(request, idempotencyKey));
    }

    /** 登记补偿命令。 */
    @PostMapping("/compensations")
    public CommonResponse<String> compensation(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                               @Valid @RequestBody CompensationCommandRequest request) {
        return responseFactory.success(applicationService.compensation(request, idempotencyKey));
    }

    /** 执行补偿。 */
    @PostMapping("/compensate")
    public CommonResponse<String> compensate(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                             @Valid @RequestBody CompensateRequest request) {
        return responseFactory.success(applicationService.compensate(request, idempotencyKey));
    }

    /** 重跑实例或分区。 */
    @PostMapping("/rerun")
    public CommonResponse<String> rerun(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                        @Valid @RequestBody RerunRequest request) {
        return responseFactory.success(applicationService.rerun(request, idempotencyKey));
    }

    /** 死信重放。 */
    @PostMapping("/dead-letters/replay")
    public CommonResponse<String> replayDeadLetter(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                   @Valid @RequestBody DeadLetterReplayRequest request) {
        return responseFactory.success(applicationService.replayDeadLetter(request, idempotencyKey));
    }

    /** 审批通过 Catch-Up 请求。 */
    @PostMapping("/catch-up/approve")
    public CommonResponse<String> approveCatchUp(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                 @Valid @RequestBody ConsoleCatchUpApprovalRequest request) {
        return responseFactory.success(applicationService.approveCatchUp(request, idempotencyKey));
    }
}
