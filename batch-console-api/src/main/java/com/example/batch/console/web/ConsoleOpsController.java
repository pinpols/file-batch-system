package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/ops")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleOpsController {

    private final ConsoleOpsApplicationService opsApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/summary")
    public CommonResponse<Map<String, Object>> summary(@RequestParam @NotBlank String tenantId) {
        return responseFactory.success(opsApplicationService.summary(tenantId));
    }
}

