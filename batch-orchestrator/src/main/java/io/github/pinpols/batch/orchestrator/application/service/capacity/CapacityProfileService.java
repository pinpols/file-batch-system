package io.github.pinpols.batch.orchestrator.application.service.capacity;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileReport.CapacityProfileCoverage;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileReport.CapacityProfileTotals;
import io.github.pinpols.batch.orchestrator.application.service.capacity.CapacityProfileReport.CapacityProfileWindow;
import io.github.pinpols.batch.orchestrator.mapper.CapacityProfileMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P2 cost profile:基于热表做近似容量画像，不做账单治理或财务成本分摊。 */
@Service
@RequiredArgsConstructor
public class CapacityProfileService {

  private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
  private static final Duration MAX_WINDOW = Duration.ofDays(31);
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final String SCOPE = "BFS_HOT_TABLES";

  private final CapacityProfileMapper capacityProfileMapper;

  @Transactional(readOnly = true)
  public CapacityProfileReport query(
      String tenantId, Instant from, Instant to, CapacityProfileGroupBy groupBy, Integer limit) {
    Guard.requireText(tenantId, "tenantId is required");
    Instant resolvedTo = to == null ? Instant.now() : to;
    Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_WINDOW) : from;
    if (!resolvedFrom.isBefore(resolvedTo)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "from must be before to");
    }
    if (Duration.between(resolvedFrom, resolvedTo).compareTo(MAX_WINDOW) > 0) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.common.invalid_argument_detail",
          "capacity profile window must not exceed 31 days");
    }
    CapacityProfileGroupBy resolvedGroupBy =
        groupBy == null ? CapacityProfileGroupBy.TENANT : groupBy;
    int resolvedLimit = normalizeLimit(limit);
    List<CapacityProfileRow> rows =
        selectRows(tenantId, resolvedFrom, resolvedTo, resolvedGroupBy, resolvedLimit).stream()
            .map(CapacityProfileRow::withRates)
            .toList();
    return new CapacityProfileReport(
        Instant.now(),
        tenantId,
        new CapacityProfileWindow(resolvedFrom, resolvedTo),
        resolvedGroupBy,
        SCOPE,
        rows,
        totals(rows),
        coverage());
  }

  private List<CapacityProfileRow> selectRows(
      String tenantId, Instant from, Instant to, CapacityProfileGroupBy groupBy, int limit) {
    return switch (groupBy) {
      case TENANT -> capacityProfileMapper.selectTenantProfile(tenantId, from, to, limit);
      case JOB -> capacityProfileMapper.selectJobProfile(tenantId, from, to, limit);
      case WORKER -> capacityProfileMapper.selectWorkerProfile(tenantId, from, to, limit);
    };
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.min(Math.max(limit, 1), MAX_LIMIT);
  }

  private CapacityProfileTotals totals(List<CapacityProfileRow> rows) {
    return new CapacityProfileTotals(
        rows.stream().mapToLong(CapacityProfileRow::instanceCount).sum(),
        rows.stream().mapToLong(CapacityProfileRow::taskCount).sum(),
        rows.stream().mapToLong(CapacityProfileRow::successCount).sum(),
        rows.stream().mapToLong(CapacityProfileRow::failureCount).sum(),
        rows.stream().mapToLong(CapacityProfileRow::totalDurationMs).sum(),
        rows.stream().mapToLong(CapacityProfileRow::totalFileBytes).sum(),
        rows.stream().mapToLong(CapacityProfileRow::processedRecords).sum());
  }

  private CapacityProfileCoverage coverage() {
    return new CapacityProfileCoverage(
        SCOPE,
        List.of(
            "processedRecords 来自 pipeline_progress,未接续跑位点的历史任务会显示为 0",
            "totalFileBytes 仅统计已关联到 job_instance/pipeline_instance 的 file_record",
            "DB CPU/IO/WAL 仍以 benchmark 报告和数据库监控为准"),
        List.of("云账单分摊", "跨平台 FinOps", "业务金额成本裁定"));
  }
}
