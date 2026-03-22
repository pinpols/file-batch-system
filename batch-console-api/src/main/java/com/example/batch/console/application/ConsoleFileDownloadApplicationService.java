package com.example.batch.console.application;

import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;

public interface ConsoleFileDownloadApplicationService {

    ResponseEntity<InputStreamResource> download(String tenantId, Long fileId, String approvalId);
}
