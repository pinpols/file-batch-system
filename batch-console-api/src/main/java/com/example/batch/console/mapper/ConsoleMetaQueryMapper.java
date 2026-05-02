package com.example.batch.console.mapper;

import com.example.batch.console.domain.view.meta.SimpleOptionView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * console meta 下拉框 (code, label) 查询 (MyBatis,迁移自 ConsoleMetaQueryRepository)。
 *
 * <p>5 个 query 同构 — 不同表(resource_queue / business_calendar / batch_window / worker_registry /
 * file_record) 抽出 enabled / status / DISTINCT 的字段作为下拉选项,全部走读副本。
 *
 * <p>{@link #bizTypeOptions(String)} 走 file_record(运行时表),其它 4 个走配置表;统一在 service 层 cache。
 */
public interface ConsoleMetaQueryMapper {

  List<SimpleOptionView> queueOptions(@Param("tenantId") String tenantId);

  List<SimpleOptionView> calendarOptions(@Param("tenantId") String tenantId);

  List<SimpleOptionView> windowOptions(@Param("tenantId") String tenantId);

  List<SimpleOptionView> workerGroupOptions(@Param("tenantId") String tenantId);

  List<SimpleOptionView> bizTypeOptions(@Param("tenantId") String tenantId);
}
