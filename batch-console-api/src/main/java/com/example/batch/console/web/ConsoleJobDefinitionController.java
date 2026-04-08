package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleJobDefinitionApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.JobDefinitionCreateRequest;
import com.example.batch.console.web.request.JobDefinitionUpdateRequest;
import com.example.batch.console.web.response.ConsoleJobDefinitionResponse;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/job-definitions")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleJobDefinitionController {

    private final ConsoleJobDefinitionApplicationService jobDefinitionApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
    public CommonResponse<ConsoleJobDefinitionResponse> detail(@PathVariable Long id,
                                                                @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(jobDefinitionApplicationService.detail(id, tenantId));
    }

    @PostMapping
    public CommonResponse<ConsoleJobDefinitionResponse> create(@Valid @RequestBody JobDefinitionCreateRequest request) {
        return responseFactory.success(jobDefinitionApplicationService.create(request));
    }

    @PutMapping("/{id}")
    public CommonResponse<ConsoleJobDefinitionResponse> update(@PathVariable Long id,
                                                                @Valid @RequestBody JobDefinitionUpdateRequest request) {
        return responseFactory.success(jobDefinitionApplicationService.update(id, request));
    }

    @PostMapping("/{id}/toggle")
    public CommonResponse<Void> toggle(@PathVariable Long id,
                                        @RequestParam("tenantId") String tenantId,
                                        @RequestParam("enabled") Boolean enabled) {
        jobDefinitionApplicationService.toggle(id, tenantId, enabled);
        return responseFactory.success(null);
    }

    @DeleteMapping("/{id}")
    public CommonResponse<Void> delete(@PathVariable Long id,
                                        @RequestParam("tenantId") String tenantId) {
        jobDefinitionApplicationService.delete(id, tenantId);
        return responseFactory.success(null);
    }

    @PostMapping("/{id}/copy")
    public CommonResponse<ConsoleJobDefinitionResponse> copy(@PathVariable Long id,
                                                              @RequestParam("tenantId") String tenantId,
                                                              @RequestParam("newJobCode") String newJobCode) {
        return responseFactory.success(jobDefinitionApplicationService.copy(id, tenantId, newJobCode));
    }
}
