package com.example.batch.console.web.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PageQueryRequest {

  @Min(1)
  private Integer pageNo = 1;

  @Min(1)
  @Max(500)
  private Integer pageSize = 20;

  /**
   * 双轨分页 cursor token (ADR-031)。
   *
   * <p>非空时 endpoint 走 cursor 模式:不查 count,不返 total,返回 {@code nextCursor} + {@code hasMore}。 空时回退到
   * pageNo 经典 offset 分页。仅大表 / 时间序端点的 controller 把这个字段透到 Mapper(其它端点忽略)。
   */
  private String cursor;
}
