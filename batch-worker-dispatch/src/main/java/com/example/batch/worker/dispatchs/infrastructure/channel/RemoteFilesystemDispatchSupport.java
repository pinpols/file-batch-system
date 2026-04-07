package com.example.batch.worker.dispatchs.infrastructure.channel;

import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.worker.dispatchs.infrastructure.DispatchFileContentResolver;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.util.StringUtils;

final class RemoteFilesystemDispatchSupport {

    private static final int MINIO_PART_SIZE = 10 * 1024 * 1024;

    private RemoteFilesystemDispatchSupport() {
    }

    static DispatchResult dispatchNas(DispatchCommand command, DispatchFileContentResolver contentResolver) {
        try {
            Map<String, Object> channelConfig = command.channelConfig();
            String remoteDir = stringProp(channelConfig, "nas_remote_directory");
            if (!StringUtils.hasText(remoteDir)) {
                remoteDir = stringProp(channelConfig, "target_endpoint");
            }
            if (!StringUtils.hasText(remoteDir)) {
                return new DispatchResult(false, null, null, false, false, "nas_remote_directory missing", null);
            }
            // H-10: normalize to prevent path traversal (matches probeNas behaviour)
            Path directory = Path.of(remoteDir).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String externalRequestId = resolveExternalRequestId(command);
            String receiptCode = resolveReceiptCode(command, externalRequestId);
            String remoteName = resolveRemoteFileName(channelConfig, command.fileRecord(), "nas_remote_file_name");
            Path target = directory.resolve(remoteName);
            try (InputStream in = contentResolver.openInputStream(command.fileRecord())) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return finishResult(command, externalRequestId, receiptCode, "uploaded via NAS", target.toString());
        } catch (Exception ex) {
            String externalRequestId = resolveExternalRequestId(command);
            String receiptCode = resolveReceiptCode(command, externalRequestId);
            return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
        }
    }

    static DispatchResult dispatchOss(DispatchCommand command,
                                      DispatchFileContentResolver contentResolver,
                                      MinioStorageProperties minioProperties,
                                      MinioClient minioClient) {
        try {
            Map<String, Object> channelConfig = command.channelConfig();
            MinioClient client = minioClient(minioProperties, minioClient);
            String bucket = firstText(channelConfig, "oss_bucket", "storage_bucket", minioProperties.getBucket());
            if (!StringUtils.hasText(bucket)) {
                return new DispatchResult(false, null, null, false, false, "oss_bucket missing", null);
            }
            String objectPrefix = firstText(channelConfig, "oss_object_prefix", "target_endpoint", "");
            String externalRequestId = resolveExternalRequestId(command);
            String receiptCode = resolveReceiptCode(command, externalRequestId);
            String remoteName = resolveRemoteFileName(channelConfig, command.fileRecord(), "oss_object_name");
            String objectName = normalizeObjectName(objectPrefix, remoteName);
            String contentType = firstText(command.fileRecord(), "mime_type", BatchFileConstants.CONTENT_TYPE_OCTET_STREAM);
            try (InputStream in = contentResolver.openInputStream(command.fileRecord())) {
                client.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(objectName)
                                .stream(in, -1, MINIO_PART_SIZE)
                                .contentType(contentType)
                                .build()
                );
            }
            return finishResult(command, externalRequestId, receiptCode, "uploaded via OSS", "oss://" + bucket + "/" + objectName);
        } catch (Exception ex) {
            String externalRequestId = resolveExternalRequestId(command);
            String receiptCode = resolveReceiptCode(command, externalRequestId);
            return new DispatchResult(false, externalRequestId, receiptCode, false, false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeNas(Map<String, Object> channelConfig) {
        try {
            String remoteDir = stringProp(channelConfig, "nas_remote_directory");
            if (!StringUtils.hasText(remoteDir)) {
                remoteDir = stringProp(channelConfig, "target_endpoint");
            }
            if (!StringUtils.hasText(remoteDir)) {
                return new DispatchChannelProbeResult(false, "nas_remote_directory missing", null);
            }
            Path directory = Path.of(remoteDir).toAbsolutePath().normalize();
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }
            if (!Files.isDirectory(directory)) {
                return new DispatchChannelProbeResult(false, "nas path is not a directory: " + directory, null);
            }
            if (!Files.isWritable(directory)) {
                return new DispatchChannelProbeResult(false,
                        "nas path not writable (read-only mount or permission denied): " + directory, null);
            }
            String probeName = BatchFileConstants.newHealthProbeName();
            Path probeFile = directory.resolve(probeName);
            Files.writeString(probeFile, "probe@" + Instant.now(), StandardCharsets.UTF_8);
            Files.deleteIfExists(probeFile);
            return new DispatchChannelProbeResult(true, "nas probe ok", probeFile.toString());
        } catch (Exception ex) {
            return new DispatchChannelProbeResult(false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeOss(Map<String, Object> channelConfig,
                                               MinioStorageProperties minioProperties,
                                               MinioClient minioClient) {
        try {
            MinioClient client = minioClient(minioProperties, minioClient);
            String bucket = firstText(channelConfig, "oss_bucket", "storage_bucket", minioProperties.getBucket());
            if (!StringUtils.hasText(bucket)) {
                return new DispatchChannelProbeResult(false, "oss_bucket missing", null);
            }
            String prefix = firstText(channelConfig, "oss_object_prefix", "target_endpoint", "");
            String objectName = normalizeObjectName(prefix, BatchFileConstants.newHealthProbeName());
            byte[] payload = ("probe@" + Instant.now()).getBytes(StandardCharsets.UTF_8);
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(new java.io.ByteArrayInputStream(payload), payload.length, MINIO_PART_SIZE)
                            .contentType(BatchFileConstants.CONTENT_TYPE_TEXT_UTF8)
                            .build()
            );
            try {
                client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            } finally {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
            }
            return new DispatchChannelProbeResult(true, "oss probe ok", "oss://" + bucket + "/" + objectName);
        } catch (Exception ex) {
            return new DispatchChannelProbeResult(false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeSftp(Map<String, Object> channelConfig) {
        try {
            String host = stringProp(channelConfig, "sftp_host");
            if (!StringUtils.hasText(host)) {
                host = stringProp(channelConfig, "target_endpoint");
            }
            if (!StringUtils.hasText(host)) {
                return new DispatchChannelProbeResult(false, "sftp_host missing", null);
            }
            int port = intProp(channelConfig, "sftp_port", 22);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
            }
            return new DispatchChannelProbeResult(true, "sftp probe ok", host + ":" + port);
        } catch (Exception ex) {
            return new DispatchChannelProbeResult(false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeSmtp(Map<String, Object> channelConfig) {
        try {
            String host = stringProp(channelConfig, "smtp_host");
            if (!StringUtils.hasText(host)) {
                host = stringProp(channelConfig, "target_endpoint");
            }
            if (!StringUtils.hasText(host)) {
                return new DispatchChannelProbeResult(false, "smtp_host missing", null);
            }
            int port = intProp(channelConfig, "smtp_port", 25);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5_000);
            }
            return new DispatchChannelProbeResult(true, "smtp probe ok", host + ":" + port);
        } catch (Exception ex) {
            return new DispatchChannelProbeResult(false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeHttp(Map<String, Object> channelConfig) {
        try {
            String endpoint = stringProp(channelConfig, "target_endpoint");
            if (!StringUtils.hasText(endpoint)) {
                return new DispatchChannelProbeResult(false, "target_endpoint missing", null);
            }
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 500) {
                return new DispatchChannelProbeResult(true, "http probe ok (status=" + status + ")", endpoint);
            }
            return new DispatchChannelProbeResult(false, "http probe failed (status=" + status + ")", endpoint);
        } catch (Exception ex) {
            return new DispatchChannelProbeResult(false, ex.getMessage(), null);
        }
    }

    static DispatchChannelProbeResult probeChannel(Map<String, Object> channelConfig,
                                                   MinioStorageProperties minioProperties,
                                                   MinioClient minioClient) {
        String channelType = String.valueOf(channelConfig.getOrDefault("channel_type", "")).toUpperCase(Locale.ROOT);
        return switch (channelType) {
            case "NAS" -> probeNas(channelConfig);
            case "OSS" -> probeOss(channelConfig, minioProperties, minioClient);
            case "SFTP" -> probeSftp(channelConfig);
            case "EMAIL" -> probeSmtp(channelConfig);
            case "API", "API_PUSH" -> probeHttp(channelConfig);
            default -> new DispatchChannelProbeResult(false, "unsupported health probe channel type: " + channelType, null);
        };
    }

    private static DispatchResult finishResult(DispatchCommand command,
                                               String externalRequestId,
                                               String receiptCode,
                                               String message,
                                               String evidence) {
        Map<String, Object> channelConfig = command.channelConfig();
        String receiptPolicy = String.valueOf(channelConfig.getOrDefault("receipt_policy", "SYNC"));
        boolean acknowledged = "NONE".equalsIgnoreCase(receiptPolicy) || "SYNC".equalsIgnoreCase(receiptPolicy);
        boolean pending = "ASYNC".equalsIgnoreCase(receiptPolicy) || "POLLING".equalsIgnoreCase(receiptPolicy);
        return new DispatchResult(true, externalRequestId, receiptCode, acknowledged, pending, message, evidence);
    }

    private static String resolveExternalRequestId(DispatchCommand command) {
        if (command.payload().externalRequestId() != null && !command.payload().externalRequestId().isBlank()) {
            return command.payload().externalRequestId();
        }
        return UUID.randomUUID().toString();
    }

    private static String resolveReceiptCode(DispatchCommand command, String externalRequestId) {
        if (command.payload().receiptCode() != null && !command.payload().receiptCode().isBlank()) {
            return command.payload().receiptCode();
        }
        return "R-" + externalRequestId;
    }

    private static String resolveRemoteFileName(Map<String, Object> channelConfig,
                                                Map<String, Object> fileRecord,
                                                String overrideKey) {
        String explicit = firstText(channelConfig, overrideKey, null);
        if (StringUtils.hasText(explicit)) {
            return sanitizeFileName(explicit);
        }
        Object original = fileRecord == null ? null : firstNonNull(fileRecord.get("original_file_name"), fileRecord.get("file_name"));
        String fallback = original == null ? BatchFileConstants.DEFAULT_FILE_NAME : String.valueOf(original);
        return sanitizeFileName(fallback);
    }

    private static String sanitizeFileName(String name) {
        if (name == null) {
            return BatchFileConstants.DEFAULT_FILE_NAME;
        }
        String n = name.trim().replace("..", "_").replace('/', '_').replace('\\', '_');
        return n.isBlank() ? BatchFileConstants.DEFAULT_FILE_NAME : n;
    }

    private static String normalizeObjectName(String prefix, String name) {
        String p = prefix == null ? "" : prefix.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p + sanitizeFileName(name);
    }

    private static String firstText(Map<String, Object> map, String key, String fallback) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return fallback;
        }
        String text = String.valueOf(v).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String firstText(Map<String, Object> map, String key1, String key2, String fallback) {
        String v = firstText(map, key1, null);
        if (StringUtils.hasText(v)) {
            return v;
        }
        return firstText(map, key2, fallback);
    }

    private static int intProp(Map<String, Object> map, String key, int defaultValue) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String stringProp(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        String text = String.valueOf(v).trim();
        return text.isEmpty() ? null : text;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static MinioClient minioClient(MinioStorageProperties properties, MinioClient minioClient) {
        if (minioClient != null) {
            return minioClient;
        }
        if (properties == null || !StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getAccessKey())
                || !StringUtils.hasText(properties.getSecretKey())) {
            throw new IllegalStateException("MinIO not configured");
        }
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
