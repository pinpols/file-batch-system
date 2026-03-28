package com.example.batch.console.web.response;

public record ConsolePresignDownloadResponse(
        String approvalNo,
        String downloadUrl
) {
}
