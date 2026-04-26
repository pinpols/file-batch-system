package com.example.batch.worker.core.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * {@code batch.step_registry} 的最小 CRUD：仅支持"清空本 module + 逐条 INSERT"的快照刷新语义， 无查询接口（查询方在 console-api
 * 侧，用自己的只读 mapper）。
 */
public interface StepRegistryMapper {

  int deleteByModule(@Param("module") String module);

  int insertEntry(
      @Param("module") String module,
      @Param("implCode") String implCode,
      @Param("implClass") String implClass);
}
