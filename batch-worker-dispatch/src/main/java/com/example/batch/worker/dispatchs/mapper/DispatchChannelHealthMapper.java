package com.example.batch.worker.dispatchs.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface DispatchChannelHealthMapper {

  List<Map<String, Object>> findEnabledProbeChannels(
      @Param("types") List<String> types, @Param("limit") int limit);

  Map<String, Object> findHealth(
      @Param("tenantId") String tenantId, @Param("channelCode") String channelCode);

  int upsertHealth(Map<String, Object> params);

  /**
   * A-3.9：CAS 抢占半开探针机会。仅当 next_probe_at &lt;= now 且 health_status 非 HEALTHY 时
   * 将 next_probe_at 更新为 newNextProbeAt；返回受影响行数。
   *
   * @return 1=本线程抢到半开通行证；0=另一线程已抢走或已恢复 HEALTHY
   */
  int tryClaimHalfOpenProbe(Map<String, Object> params);
}
