package com.example.batch.console.domain.job.mapper;

import com.example.batch.common.model.PageRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface BatchDayMapper {

  List<Map<String, Object>> selectByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("fromBizDate") LocalDate fromBizDate,
      @Param("toBizDate") LocalDate toBizDate,
      @Param("pageRequest") PageRequest pageRequest);

  long countByQuery(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("fromBizDate") LocalDate fromBizDate,
      @Param("toBizDate") LocalDate toBizDate);

  Map<String, Object> selectWindow(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<Map<String, Object>> selectJobSummaries(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<String> selectFailedJobCodes(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);
}
