package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleConfigApplicationService;
import com.example.batch.console.domain.entity.ConfigChangeLogEntity;
import com.example.batch.console.domain.entity.ConfigReleaseEntity;
import com.example.batch.console.domain.entity.SecretVersionEntity;
import com.example.batch.console.support.ConsoleResponseFactory;
import com.example.batch.console.domain.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.domain.query.ConfigReleaseQueryRequest;
import com.example.batch.console.domain.query.SecretVersionQueryRequest;
import com.example.batch.console.domain.request.ConfigReleaseActionRequest;
import com.example.batch.console.domain.request.ConfigReleaseUpsertRequest;
import com.example.batch.console.domain.request.SecretVersionRotateRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/config")
@RequiredArgsConstructor
public class ConsoleConfigController {

    private final ConsoleConfigApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/releases")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<List<ConfigReleaseEntity>> configReleases(@Valid @ModelAttribute ConfigReleaseQueryRequest request) {
        return responseFactory.success(applicationService.configReleases(request));
    }

    @PostMapping("/releases")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Long> createConfigRelease(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody ConfigReleaseUpsertRequest request) {
        return responseFactory.success(applicationService.createConfigRelease(request));
    }

    @PostMapping("/releases/{releaseId}/publish")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<String> publishConfigRelease(@PathVariable Long releaseId,
                                                       @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                       @Valid @RequestBody ConfigReleaseActionRequest request) {
        return responseFactory.success(applicationService.publishConfigRelease(releaseId, request));
    }

    @PostMapping("/releases/{releaseId}/gray")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<String> grayConfigRelease(@PathVariable Long releaseId,
                                                    @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody ConfigReleaseActionRequest request) {
        return responseFactory.success(applicationService.grayConfigRelease(releaseId, request));
    }

    @PostMapping("/releases/{releaseId}/rollback")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<String> rollbackConfigRelease(@PathVariable Long releaseId,
                                                        @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                        @Valid @RequestBody ConfigReleaseActionRequest request) {
        return responseFactory.success(applicationService.rollbackConfigRelease(releaseId, request));
    }

    @GetMapping("/secrets")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<List<SecretVersionEntity>> secretVersions(@Valid @ModelAttribute SecretVersionQueryRequest request) {
        return responseFactory.success(applicationService.secretVersions(request));
    }

    @PostMapping("/secrets/rotate")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Long> rotateSecretVersion(@RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                    @Valid @RequestBody SecretVersionRotateRequest request) {
        return responseFactory.success(applicationService.rotateSecretVersion(request));
    }

    @GetMapping("/change-logs")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<List<ConfigChangeLogEntity>> configChangeLogs(@Valid @ModelAttribute ConfigChangeLogQueryRequest request) {
        return responseFactory.success(applicationService.configChangeLogs(request));
    }
}
