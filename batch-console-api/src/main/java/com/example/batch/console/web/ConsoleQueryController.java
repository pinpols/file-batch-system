package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleQueryApplicationService;
import com.example.batch.console.domain.entity.DeadLetterTaskEntity;
import com.example.batch.console.domain.entity.FileRecordEntity;
import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.entity.PendingCatchUpEntity;
import com.example.batch.console.domain.entity.RetryScheduleEntity;
import com.example.batch.console.domain.entity.WorkerRegistryEntity;
import com.example.batch.console.support.ConsoleResponseFactory;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.DeadLetterQueryRequest;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.query.FileChainQueryRequest;
import com.example.batch.console.web.query.FileDispatchRecordQueryRequest;
import com.example.batch.console.web.query.FilePipelineQueryRequest;
import com.example.batch.console.web.query.FilePipelineStepQueryRequest;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.query.JobInstanceQueryRequest;
import com.example.batch.console.web.query.PendingCatchUpQueryRequest;
import com.example.batch.console.web.query.RetryScheduleQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import com.example.batch.common.dto.CommonResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/query")
@RequiredArgsConstructor
public class ConsoleQueryController {

    private final ConsoleQueryApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/audits")
    public CommonResponse<List<Map<String, Object>>> audits(@Valid @ModelAttribute AuditLogQueryRequest request) {
        return responseFactory.success(applicationService.auditLogs(request));
    }

    @GetMapping("/files")
    public CommonResponse<List<FileRecordEntity>> files(@Valid @ModelAttribute FileChainQueryRequest request) {
        return responseFactory.success(applicationService.fileChains(request));
    }

    @GetMapping("/file-pipelines")
    public CommonResponse<List<Map<String, Object>>> filePipelines(@Valid @ModelAttribute FilePipelineQueryRequest request) {
        return responseFactory.success(applicationService.filePipelines(request));
    }

    @GetMapping("/file-pipeline-steps")
    public CommonResponse<List<Map<String, Object>>> filePipelineSteps(@Valid @ModelAttribute FilePipelineStepQueryRequest request) {
        return responseFactory.success(applicationService.filePipelineSteps(request));
    }

    @GetMapping("/file-dispatches")
    public CommonResponse<List<Map<String, Object>>> fileDispatches(@Valid @ModelAttribute FileDispatchRecordQueryRequest request) {
        return responseFactory.success(applicationService.fileDispatchRecords(request));
    }

    @GetMapping("/file-channels")
    public CommonResponse<List<Map<String, Object>>> fileChannels(@Valid @ModelAttribute FileChannelQueryRequest request) {
        return responseFactory.success(applicationService.fileChannels(request));
    }

    @GetMapping("/file-templates")
    public CommonResponse<List<Map<String, Object>>> fileTemplates(@Valid @ModelAttribute FileTemplateQueryRequest request) {
        return responseFactory.success(applicationService.fileTemplates(request));
    }

    @GetMapping("/instances")
    public CommonResponse<List<JobInstanceEntity>> instances(@Valid @ModelAttribute JobInstanceQueryRequest request) {
        return responseFactory.success(applicationService.jobInstances(request));
    }

    @GetMapping("/dead-letters")
    public CommonResponse<List<DeadLetterTaskEntity>> deadLetters(@Valid @ModelAttribute DeadLetterQueryRequest request) {
        return responseFactory.success(applicationService.deadLetters(request));
    }

    @GetMapping("/retries")
    public CommonResponse<List<RetryScheduleEntity>> retries(@Valid @ModelAttribute RetryScheduleQueryRequest request) {
        return responseFactory.success(applicationService.retries(request));
    }

    @GetMapping("/catch-up-approvals")
    public CommonResponse<List<PendingCatchUpEntity>> catchUpApprovals(@Valid @ModelAttribute PendingCatchUpQueryRequest request) {
        return responseFactory.success(applicationService.pendingCatchUps(request));
    }

    @GetMapping("/workers")
    public CommonResponse<List<WorkerRegistryEntity>> workers(@Valid @ModelAttribute WorkerRegistryQueryRequest request) {
        return responseFactory.success(applicationService.workers(request));
    }
}
