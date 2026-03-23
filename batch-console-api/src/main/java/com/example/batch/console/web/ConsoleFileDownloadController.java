package com.example.batch.console.web;

import com.example.batch.console.application.ConsoleFileDownloadApplicationService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/console/files")
@RequiredArgsConstructor
public class ConsoleFileDownloadController {

    private final ConsoleFileDownloadApplicationService applicationService;

    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long fileId,
                                                        @RequestParam @NotNull String tenantId,
                                                        @RequestParam(required = false) String approvalId) {
        return applicationService.download(tenantId, fileId, approvalId);
    }
}
