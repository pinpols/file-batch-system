package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleWorkflowDefinitionApplicationService;
import com.example.batch.console.application.ConsoleWorkflowDefinitionApplicationService.DagValidationResult;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.WorkflowDefinitionSaveRequest;
import com.example.batch.console.web.response.WorkflowDefinitionDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/workflow-definitions")
@RequiredArgsConstructor
public class ConsoleWorkflowDefinitionController {

    private final ConsoleWorkflowDefinitionApplicationService workflowDefinitionApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<WorkflowDefinitionDetailResponse> getById(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(workflowDefinitionApplicationService.getById(id, tenantId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<WorkflowDefinitionDetailResponse> create(
            @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
        return responseFactory.success(workflowDefinitionApplicationService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<WorkflowDefinitionDetailResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowDefinitionSaveRequest request) {
        return responseFactory.success(workflowDefinitionApplicationService.update(id, request));
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<String> toggleEnabled(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("enabled") Boolean enabled) {
        workflowDefinitionApplicationService.toggleEnabled(id, tenantId, enabled);
        return responseFactory.success("OK");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<String> delete(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId) {
        workflowDefinitionApplicationService.delete(id, tenantId);
        return responseFactory.success("OK");
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<DagValidationResult> validate(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(workflowDefinitionApplicationService.validate(id, tenantId));
    }
}
