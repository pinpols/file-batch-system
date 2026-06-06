package com.example.batch.common.storage;

import java.util.List;

/**
 * 一页列举结果。
 *
 * @param objects 本页对象列表
 * @param nextMarker 下一页起始 marker；{@code null} 表示已到末页
 */
public record ObjectListing(List<ObjectSummary> objects, String nextMarker) {}
