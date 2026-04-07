package com.example.batch.common.validation;

import java.nio.file.Path;

/** D-5: 路径清洗工具，防止路径遍历攻击。所有外部输入的文件路径必须经过此类校验。 */
public final class PathSanitizer {

    private PathSanitizer() {
    }

    /** 校验并规范化路径，拒绝包含 {@code ..} 的遍历尝试。 */
    public static Path sanitize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path must not be null or blank");
        }
        if (rawPath.contains("..")) {
            throw new SecurityException("path contains traversal sequence '..': " + rawPath);
        }
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    /** 校验并规范化路径，同时确保路径在 baseDir 沙箱目录内。 */
    public static Path sanitize(String rawPath, Path baseDir) {
        Path normalized = sanitize(rawPath);
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedBase)) {
            throw new SecurityException(
                    "path escapes allowed base directory: path=" + normalized + ", baseDir=" + normalizedBase);
        }
        return normalized;
    }
}
