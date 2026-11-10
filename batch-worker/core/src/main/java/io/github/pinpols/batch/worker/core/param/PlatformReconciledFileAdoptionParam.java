package io.github.pinpols.batch.worker.core.param;

import java.time.LocalDate;

/** 经扫描准入后的对账文件接管参数。 */
public record PlatformReconciledFileAdoptionParam(
    Long fileId,
    String tenantId,
    String storageBucket,
    String storagePath,
    LocalDate bizDate,
    String bizType,
    String arrivalChannelCode,
    long fileSizeBytes,
    String etag,
    String checksumType,
    String checksumValue,
    String metadataJson) {}
