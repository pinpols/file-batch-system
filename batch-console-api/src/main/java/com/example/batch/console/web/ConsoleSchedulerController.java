package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleTriggerProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.common.dto.CommonResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/console/scheduler")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleSchedulerController {

    private final ConsoleTriggerProxyService triggerProxyService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/status")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<Map<String, String>> status() {
        return responseFactory.success(triggerProxyService.schedulerStatus());
    }

    @PostMapping("/pause-all")
    public CommonResponse<Map<String, String>> pauseAll() {
        return responseFactory.success(triggerProxyService.schedulerPauseAll());
    }

    @PostMapping("/resume-all")
    public CommonResponse<Map<String, String>> resumeAll() {
        return responseFactory.success(triggerProxyService.schedulerResumeAll());
    }
}
