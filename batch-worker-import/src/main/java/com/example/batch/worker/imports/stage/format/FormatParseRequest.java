package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportPayload;

/** Immutable parameter object passed to each {@link FormatParser}. */
public record FormatParseRequest(
    String payloadText,
    byte[] binaryPayload,
    ImportPayload importPayload,
    Object templateConfig,
    boolean preserveLogicalRow) {}
