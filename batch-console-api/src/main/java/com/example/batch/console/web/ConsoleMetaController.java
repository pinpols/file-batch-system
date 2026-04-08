package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleMetaQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/meta")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleMetaController {

    private final ConsoleMetaQueryService queryService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/enums")
    public CommonResponse<Map<String, List<EnumItem>>> enums() {
        return responseFactory.success(queryService.enums());
    }

    @GetMapping("/queues")
    public CommonResponse<List<SimpleOption>> queues(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.queues(tenantId));
    }

    @GetMapping("/calendars")
    public CommonResponse<List<SimpleOption>> calendars(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.calendars(tenantId));
    }

    @GetMapping("/windows")
    public CommonResponse<List<SimpleOption>> windows(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.windows(tenantId));
    }

    @GetMapping("/worker-groups")
    public CommonResponse<List<SimpleOption>> workerGroups(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.workerGroups(tenantId));
    }

    public record EnumItem(String code, String label) {}
    public record SimpleOption(String code, String label) {}
}
