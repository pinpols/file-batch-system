package com.example.batch.orchestrator.application.service.readiness;

import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 上游就绪查询服务(ADR-043 依赖感知 fire)。
 *
 * <p>orchestrator 是唯一状态主机,就绪判定基于其权威 job_instance 终态。
 *
 * <p>trigger fire 前经 /internal/readiness 只读调本服务,不直连状态表。
 *
 * <p>v1 只支持「上游 JOB 该批次日已 SUCCESS」,FILE_GROUP 就绪留作后续扩展。
 */
@Service
@RequiredArgsConstructor
public class ReadinessService {

  private final JobInstanceMapper jobInstanceMapper;

  /**
   * 上游 job 在指定批次日是否已就绪(**最新一次 attempt** 为 SUCCESS)。
   *
   * <p>口径用"最新 attempt"而非"存在任意 SUCCESS":先成功后 rerun 失败 / rerun 正在跑时,最新 attempt 非 SUCCESS → not
   * ready,避免下游按已被推翻的过期结果启动(结算级误放行)。
   *
   * @param tenantId 租户
   * @param jobCode 上游 job code
   * @param bizDate 对齐后的批次日
   */
  public ReadinessResult checkJobReady(String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return ReadinessResult.ofNotReady("invalid-readiness-query");
    }
    String latestStatus = jobInstanceMapper.selectLatestStatusByBizDate(tenantId, jobCode, bizDate);
    return "SUCCESS".equals(latestStatus)
        ? ReadinessResult.ofReady()
        : ReadinessResult.ofNotReady("upstream-job-not-success");
  }
}
