package io.github.pinpols.batch.orchestrator.application.service.version;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
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
   * 按 (tenant, jobCode, bizDate) 查询可消费版本。
   *
   * <p>这里 intentionally 使用「最新版本必须是 EFFECTIVE」语义：同一账期 rerun 已产生 PENDING / FAILED 最新 attempt 时，不能继续把旧
   * EFFECTIVE 暴露给 readiness / 跨账期依赖消费。
   */
  public Optional<ResultVersionEntity> findEffectiveByJob(
      String tenantId, String jobCode, LocalDate bizDate) {
    return findLatestByJob(tenantId, jobCode, bizDate)
        .filter(row -> "EFFECTIVE".equals(row.status()));
  }

  /** 按 (tenant, jobCode, bizDate) 查询最新版本；用于 readiness 阻断旧 EFFECTIVE 被新 attempt 覆盖前误消费。 */
  public Optional<ResultVersionEntity> findLatestByJob(
      String tenantId, String jobCode, LocalDate bizDate) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || bizDate == null) {
      return Optional.empty();
    }
    List<ResultVersionEntity> versions =
        listVersions(tenantId, jobBusinessKey(jobCode, bizDate), 1);
    return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0));
  }

  /** 列出某 business_key 的所有版本（按 version_no desc，含 PENDING / EFFECTIVE / SUPERSEDED / ARCHIVED）。 */
  public List<ResultVersionEntity> listVersions(String tenantId, String businessKey, int limit) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(businessKey) || limit <= 0) {
      return Collections.emptyList();
    }
    return resultVersionMapper.listVersionsByBusinessKey(tenantId, businessKey, limit);
  }

  /** 按租户和版本 id 查询详情；Controller 不直接触碰 Mapper，避免绕过应用层的不变量。 */
  public ResultVersionEntity getByIdOrThrow(String tenantId, Long id) {
    if (!Texts.hasText(tenantId) || id == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.result_version.invalid_argument");
    }
    ResultVersionEntity entity = resultVersionMapper.selectById(tenantId, id);
    if (entity == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
    }
    return entity;
  }

  private String jobBusinessKey(String jobCode, LocalDate bizDate) {
    return "job:" + jobCode + ":" + bizDate;
  }
}
