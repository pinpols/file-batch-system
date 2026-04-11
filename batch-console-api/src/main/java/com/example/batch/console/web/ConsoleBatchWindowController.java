package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleBatchWindowApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.BatchWindowCreateRequest;
import com.example.batch.console.web.request.BatchWindowUpdateRequest;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/console/batch-windows")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleBatchWindowController {

    private final ConsoleBatchWindowApplicationService batchWindowApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    public CommonResponse<PageResponse<Map<String, Object>>> list(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "windowCode", required = false) String windowCode,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return responseFactory.success(
                batchWindowApplicationService.list(
                        tenantId, windowCode, enabled, pageNo, pageSize));
    }

    @PostMapping
    public CommonResponse<Map<String, Object>> create(
            @Valid @RequestBody BatchWindowCreateRequest request) {
        return responseFactory.success(batchWindowApplicationService.create(request));
    }

    @PutMapping("/{id}")
    public CommonResponse<Map<String, Object>> update(
            @PathVariable Long id, @Valid @RequestBody BatchWindowUpdateRequest request) {
        return responseFactory.success(batchWindowApplicationService.update(id, request));
    }

    @PostMapping("/{id}/toggle")
    public CommonResponse<Void> toggle(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("enabled") Boolean enabled) {
        batchWindowApplicationService.toggle(id, tenantId, enabled);
        return responseFactory.success(null);
    }
}
