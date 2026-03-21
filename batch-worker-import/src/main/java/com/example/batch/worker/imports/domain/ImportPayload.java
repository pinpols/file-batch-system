package com.example.batch.worker.imports.domain;

import java.util.Map;

public record ImportPayload(
        String fileCode,
        String fileName,
        String originalFileName,
        String bizType,
        String fileFormatType,
        String charset,
        String targetCharset,
        String checksumType,
        String checksumValue,
        String sourceType,
        String sourceRef,
        String storageType,
        String storagePath,
        String storageBucket,
        String templateCode,
        String batchNo,
        String content,
        String contentBase64,
        String delimiter,
        Integer headerRows,
        Integer footerRows,
        Boolean withHeader,
        Map<String, Object> metadata
) {
}
