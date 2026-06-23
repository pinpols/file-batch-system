package io.github.pinpols.batch.orchestrator.application.service.version;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * ADR-017 §决策 §实施分阶段 Stage 3 — 结果版本读取入口。
 *
 * <p>业务消费方原本扫 {@code job_instance} 拿"最近一次成功"，现在统一改读 {@code result_version} 的 EFFECTIVE 行：
 *
 * <ul>
 *   <li>{@link #findEffective(String, String)} —— 直接按 business_key 找当前生效版本；
 *   <li>{@link #findEffectiveByJob(String, String, LocalDate)} —— 按 job_code + biz_date 派生
 *       business_key 的便捷入口；
 *   <li>{@link #listVersions(String, String, int)} —— 列举某 business_key 的所有版本（含 SUPERSEDED /
 *       ARCHIVED）， 供 console 版本视图、ops 排查用。
 * </ul>
 *
 * <p>本 Stage 不接 Redis 缓存；后续按真实读 QPS 评估再加（参考 {@code OrchestratorConfigCacheService}）。
 */
@Service
@RequiredArgsConstructor
public class ResultVersionQueryService {

  private final ResultVersionMapper resultVersionMapper;

  /**
   * 找 (tenant, business_key) 的当前 EFFECTIVE 版本；同 business_key 至多 1 行 EFFECTIVE（ADR-017 不变量），由
   * partial unique index 保证。
   */
  public Optional<ResultVersionEntity> findEffective(String tenantId, String businessKey) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(businessKey)) {
      return Optional.empty();
    }
    return Optional.ofNullable(resultVersionMapper.selectEffective(tenantId, businessKey));
  }

  /**
   * 按 (tenant, jobCode, bizDate) 派生 business_key 后查 EFFECTIVE。便捷入口，等价于：
   *
   * <pre>findEffective(tenantId, "job:" + jobCode + ":" + bizDate)</pre>
   */
  public Optional<ResultVersionEntity> findEffectiveByJob(
      String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return Optional.empty();
    }
    return findEffective(tenantId, "job:" + jobCode + ":" + bizDate);
  }

  /** 列出某 business_key 的所有版本（按 version_no desc，含 PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED）。 */
  public List<ResultVersionEntity> listVersions(String tenantId, String businessKey, int limit) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(businessKey) || limit <= 0) {
      return Collections.emptyList();
    }
    return resultVersionMapper.listVersionsByBusinessKey(tenantId, businessKey, limit);
  }
}
