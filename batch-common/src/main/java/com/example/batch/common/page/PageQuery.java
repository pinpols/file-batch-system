package com.example.batch.common.page;

/**
 * 分页请求的统一类型 — 双轨并存(ADR-031)。
 *
 * <p>{@link Offset}:经典 {@code pageNo + pageSize},返回 total + 跳页;{@link Cursor}:不透明 token,内部
 * base64(JSON 排序键),不返 total,支持 infinite scroll / 大表深翻页稳定。
 *
 * <p>Controller 统一用 {@link #of(Integer, String, int)} 构造,优先 cursor → offset 回退,默认 pageNo=1。
 */
public sealed interface PageQuery permits PageQuery.Offset, PageQuery.Cursor {

  /** 每页最大条数。 */
  int pageSize();

  /**
   * Offset 模式:适合配置 / 小表 / 需要跳页或总数的场景。
   *
   * @param pageNo 从 1 开始,&lt;1 自动归 1
   * @param pageSize &lt;1 时默认 20
   */
  record Offset(int pageNo, int pageSize) implements PageQuery {
    public Offset {
      if (pageNo < 1) pageNo = 1;
      if (pageSize < 1) pageSize = 20;
    }

    /** {@code (pageNo - 1) * pageSize} — Mapper xml 里用 {@code #{page.offset}}。 */
    public long offset() {
      return (long) (pageNo - 1) * pageSize;
    }
  }

  /**
   * Cursor 模式:适合大表 / 时间序 / infinite scroll。token 由 {@link CursorCodec} 编解码,解码失败时 SQL 应自然返回 0
   * 行(WHERE 谓词全部命中 0 行)。
   *
   * @param token 不透明 base64 字符串。null/blank 表示首页
   */
  record Cursor(String token, int pageSize) implements PageQuery {
    public Cursor {
      if (pageSize < 1) pageSize = 20;
    }

    /** 是否为首页(token 为空)。 */
    public boolean isFirst() {
      return token == null || token.isBlank();
    }
  }

  /**
   * 优先 cursor,回退 offset。供 Controller 把 {@code ?cursor=&pageNo=&pageSize=} 三个 query param 统一成
   * PageQuery。
   *
   * @param pageNo nullable;null 或 &lt;1 时归 1
   * @param cursor nullable;非 blank 时优先返回 Cursor
   * @param pageSize 默认 20
   */
  static PageQuery of(Integer pageNo, String cursor, int pageSize) {
    if (cursor != null && !cursor.isBlank()) {
      return new Cursor(cursor, pageSize);
    }
    return new Offset(pageNo == null ? 1 : pageNo, pageSize);
  }
}
