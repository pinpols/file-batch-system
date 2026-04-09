package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.entity.ArchivePolicyEntity;
import com.example.batch.console.service.ConsoleArchivePolicyService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据归档/清理策略管理：配置各运行态表的保留天数、是否归档、是否清理。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops/archive-policies")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleArchivePolicyController {

    private final ConsoleArchivePolicyService archivePolicyService;
    private final ConsoleResponseFactory responseFactory;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    @GetMapping
    public CommonResponse<List<ArchivePolicyEntity>> list(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(archivePolicyService.list(tenantId));
    }

    @PutMapping
    public CommonResponse<Void> upsert(@RequestParam("tenantId") String tenantId,
                                       @Valid @RequestBody UpsertArchivePolicyRequest request) {
        String operator = requestMetadataResolver.current().operatorId();
        archivePolicyService.upsert(tenantId, request.targetTable(), request.retentionDays(),
                request.archiveEnabled(), request.cleanupEnabled(),
                request.batchSize(), request.description(), operator);
        return responseFactory.success(null);
    }

    record UpsertArchivePolicyRequest(
            @NotBlank @Size(max = 64) String targetTable,
            @Min(1) int retentionDays,
            boolean archiveEnabled,
            boolean cleanupEnabled,
            @Min(100) int batchSize,
            @Size(max = 512) String description) {
    }
}
