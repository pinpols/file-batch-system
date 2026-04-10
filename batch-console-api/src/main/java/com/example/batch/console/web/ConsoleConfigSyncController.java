package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleConfigSyncApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ConfigSyncExportRequest;
import com.example.batch.console.web.request.ConfigSyncImportRequest;
import com.example.batch.console.web.request.ConfigSyncPreviewRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/config/sync")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleConfigSyncController {

    private final ConsoleConfigSyncApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/export")
    public CommonResponse<Map<String, Object>> export(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ConfigSyncExportRequest request) {
        return responseFactory.success(applicationService.export(request));
    }

    @PostMapping("/preview")
    public CommonResponse<Map<String, Object>> preview(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ConfigSyncPreviewRequest request) {
        return responseFactory.success(applicationService.preview(request));
    }

    @PostMapping("/import")
    public CommonResponse<Map<String, Object>> importBundle(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ConfigSyncImportRequest request) {
        return responseFactory.success(applicationService.importBundle(request));
    }

    @GetMapping("/logs")
    public CommonResponse<List<Map<String, Object>>> logs(@RequestParam("tenantId") String tenantId,
                                                          @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return responseFactory.success(applicationService.logs(tenantId, limit));
    }
}
