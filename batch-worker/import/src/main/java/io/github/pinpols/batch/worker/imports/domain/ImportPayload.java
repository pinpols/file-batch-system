package io.github.pinpols.batch.worker.imports.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 导入任务的原始业务载荷，从 Kafka 消息或任务 payload 字段反序列化而来。 描述待导入文件的来源（sourceType/sourceRef）、存储位置、格式参数（分隔符、表头行数等）
 * 及校验信息（checksumType/checksumValue），是导入流水线的数据入口。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
    Map<String, Object> metadata) {}
