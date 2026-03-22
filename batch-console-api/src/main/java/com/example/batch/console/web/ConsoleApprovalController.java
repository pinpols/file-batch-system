package com.example.batch.console.web;

import com.example.batch.console.service.ConsoleApprovalApplicationService;
import com.example.batch.console.support.ConsoleResponseFactory;
import com.example.batch.console.domain.request.ApprovalActionRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/approvals")
@RequiredArgsConstructor
public class ConsoleApprovalController {

    private final ConsoleApprovalApplicationService approvalApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/{approvalNo}/approve")
    public CommonResponse<String> approve(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                          @PathVariable String approvalNo,
                                          @Valid @RequestBody ApprovalActionRequest request) {
        return responseFactory.success(approvalApplicationService.approve(request.getTenantId(), approvalNo, request.getOperatorId(), request.getReason()));
    }

    @PostMapping("/{approvalNo}/reject")
    public CommonResponse<String> reject(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                         @PathVariable String approvalNo,
                                         @Valid @RequestBody ApprovalActionRequest request) {
        return responseFactory.success(approvalApplicationService.reject(request.getTenantId(), approvalNo, request.getOperatorId(), request.getReason()));
    }
}
