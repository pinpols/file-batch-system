package com.example.batch.common.storage;

import java.time.Instant;

/**
 * 单个对象的列举摘要。
 *
 * @param key 对象键
 * @param size 字节大小
 * @param lastModified 最后修改时间
 * @param etag 变更令牌（非内容哈希，仅用于变更检测）
 */
public record ObjectSummary(String key, long size, Instant lastModified, String etag) {}
