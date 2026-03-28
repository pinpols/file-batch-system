package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleWorkerApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.DrainWorkerRequest;
import com.example.batch.console.web.request.ForceOfflineWorkerRequest;
import com.example.batch.console.web.response.ConsoleWorkerClaimedTaskResponse;
import com.example.batch.console.web.response.ConsoleWorkerRegistryResponse;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/workers")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleWorkerController {

    private final ConsoleWorkerApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/{workerCode}/drain")
    public CommonResponse<ConsoleWorkerRegistryResponse> drain(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable String workerCode,
            @Valid @RequestBody DrainWorkerRequest request) {
        return responseFactory.success(applicationService.drain(workerCode, request, idempotencyKey));
    }

    @PostMapping("/{workerCode}/force-offline")
    public CommonResponse<ConsoleWorkerRegistryResponse> forceOffline(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable String workerCode,
            @Valid @RequestBody ForceOfflineWorkerRequest request) {
        return responseFactory.success(applicationService.forceOffline(workerCode, request, idempotencyKey));
    }

    @GetMapping("/{workerCode}/claimed-tasks")
    public CommonResponse<List<ConsoleWorkerClaimedTaskResponse>> claimedTasks(@PathVariable String workerCode,
                                                                  @RequestParam String tenantId) {
        return responseFactory.success(applicationService.claimedTasks(tenantId, workerCode));
    }
}
