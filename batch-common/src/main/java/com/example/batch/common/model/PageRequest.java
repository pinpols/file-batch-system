package com.example.batch.common.model;

public record PageRequest(int pageNo, int pageSize) {

  /**
   * pageSize 硬上限 — 防 DoS:前端 / API 误传 pageSize=100000 会触发 PG full scan + 内存爆。 200 与 load-tests 使用的
   * 50 同量级,常规分页够用;批量导出走单独的 export 路径,不走 PageRequest。
   */
  public static final int MAX_PAGE_SIZE = 200;

  public PageRequest {
    if (pageNo < 1) {
      pageNo = 1;
    }
    if (pageSize < 1) {
      pageSize = 20;
    }
    if (pageSize > MAX_PAGE_SIZE) {
      pageSize = MAX_PAGE_SIZE;
    }
  }
}
