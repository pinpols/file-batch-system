package io.github.pinpols.batch.common.model;

import java.util.List;

/**
 * 分页响应。
 *
 * <p>双轨支持(ADR-031):
 *
 * <ul>
 *   <li><b>Offset 模式</b>:total / pageNo 有意义,nextCursor=null,hasMore 通过 pageNo*pageSize 与 total 计算
 *   <li><b>Cursor 模式</b>:total=0,pageNo=0,nextCursor 非 null 表示还有下一页,hasMore 同义
 * </ul>
 *
 * <p>旧 4 字段构造器保留,既有调用方零修改;cursor 端点用 6 字段构造器或 {@link #cursor} 工厂。
 */
public record PageResponse<T>(
    long total, int pageNo, int pageSize, List<T> items, String nextCursor, boolean hasMore) {

  /** 4 字段构造器(offset 模式,既有调用方默认走这里;hasMore 自动算)。 */
  public PageResponse(long total, int pageNo, int pageSize, List<T> items) {
    this(total, pageNo, pageSize, items, null, (long) pageNo * pageSize < total);
  }

  /** Cursor 模式工厂:total/pageNo=0,带 cursor。 */
  public static <T> PageResponse<T> cursor(List<T> items, int pageSize, String nextCursor) {
    return new PageResponse<>(0L, 0, pageSize, items, nextCursor, nextCursor != null);
  }
}
