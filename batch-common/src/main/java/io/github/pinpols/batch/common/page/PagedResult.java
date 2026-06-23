package io.github.pinpols.batch.common.page;

import java.util.List;

/**
 * 分页响应的统一类型 — 双轨并存(ADR-031)。
 *
 * <p>两种模式的字段语义:
 *
 * <ul>
 *   <li><b>Offset 模式</b>:items + total + pageNo,nextCursor = null
 *   <li><b>Cursor 模式</b>:items + nextCursor + hasMore,total/pageNo = null
 * </ul>
 *
 * <p>FE 看响应里哪侧字段非 null 决定渲染 page 或 cursor pagination UI。
 *
 * @param items 数据列表
 * @param total offset 模式总条数;cursor 模式 null
 * @param pageNo offset 模式当前页号;cursor 模式 null
 * @param nextCursor cursor 模式的下一页 token;offset 模式 null;hasMore=false 时也是 null
 * @param hasMore 是否还有下一页;offset 模式据 total 计算,cursor 模式据本次取到 size==pageSize 判定
 */
public record PagedResult<T>(
    List<T> items, Long total, Integer pageNo, String nextCursor, boolean hasMore) {

  /** Offset 模式工厂。 */
  public static <T> PagedResult<T> offset(List<T> items, long total, int pageNo, int pageSize) {
    boolean more = (long) pageNo * pageSize < total;
    return new PagedResult<>(items, total, pageNo, null, more);
  }

  /**
   * Cursor 模式工厂。约定:size==pageSize 时认为「可能还有」,后端调用方据排序键编码 nextCursor;否则 nextCursor=null。
   *
   * @param items 已截取到 pageSize 的列表
   * @param nextCursor null 表示没有下一页(可能因为不足 pageSize)
   */
  public static <T> PagedResult<T> cursor(List<T> items, String nextCursor) {
    return new PagedResult<>(items, null, null, nextCursor, nextCursor != null);
  }

  /** 空 cursor 响应(用于 token 解码失败或排序键已被删的安全降级)。 */
  public static <T> PagedResult<T> emptyCursor() {
    return new PagedResult<>(List.of(), null, null, null, false);
  }
}
