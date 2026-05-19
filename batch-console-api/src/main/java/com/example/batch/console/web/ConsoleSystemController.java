package com.example.batch.console.web;

import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder;
import com.example.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import com.example.batch.console.web.response.CronPreviewResponse;
import com.example.batch.console.web.response.MaintenanceStatusResponse;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import org.quartz.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Console 系统级公开接口:健康/维护状态/cron 工具等不依赖业务数据的端点。 */
@RestController
@RequestMapping("/api/console/system")
@RequiredArgsConstructor
public class ConsoleSystemController {

  /** cron-preview 单次最多返回的执行时刻数。防止用户传巨大 count 拖慢响应。 */
  private static final int CRON_PREVIEW_MAX_COUNT = 20;

  /** cron-preview 默认返回的执行时刻数。 */
  private static final int CRON_PREVIEW_DEFAULT_COUNT = 3;

  private final MaintenanceStateHolder maintenanceStateHolder;
  private final BatchTimezoneProvider timezoneProvider;

  /**
   * 维护状态探活。前端启动 + 30s 轮询调用,据此切换全局 banner / 降级页。
   *
   * <p>注意:本端点在维护期间仍然返回 200(由 {@code MaintenanceModeFilter} 白名单放行),否则前端无法探测恢复时机。
   */
  @GetMapping("/maintenance")
  public CommonResponse<MaintenanceStatusResponse> maintenanceStatus() {
    MaintenanceState state = maintenanceStateHolder.current();
    MaintenanceStatusResponse response =
        new MaintenanceStatusResponse(
            state.enabled(),
            state.readOnly(),
            state.message(),
            state.etaAt() != null ? state.etaAt().toString() : null,
            state.affectedServices());
    return CommonResponse.success(response);
  }

  /**
   * Cron 表达式预览:校验 + 计算下 N 次执行时刻(ISO-8601 UTC)。
   *
   * <p>用 Quartz {@link CronExpression} 解析,与实际调度引擎同一份代码,保证 FE 展示的时间和真实触发时间不漂。FE `CronExprInput` 输入防抖
   * 300ms 后调用,展示「最近 3 次执行」。
   *
   * <p>时区使用 {@code batch.timezone.default-zone}(默认 Asia/Shanghai),与 trigger 模块的 scheduler 默认配置对齐。
   *
   * @param expr Quartz 6/7 字段表达式(秒 分 时 日 月 星期 [年])
   * @param count 返回时刻数,默认 3,上限 20
   */
  @GetMapping("/cron-preview")
  public CommonResponse<CronPreviewResponse> cronPreview(
      @RequestParam("expr") String expr,
      @RequestParam(value = "count", required = false) Integer count) {
    int n =
        count == null
            ? CRON_PREVIEW_DEFAULT_COUNT
            : Math.max(1, Math.min(count, CRON_PREVIEW_MAX_COUNT));
    String trimmed = expr == null ? "" : expr.trim();
    if (trimmed.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument_detail", "expr is required");
    }
    CronExpression cron;
    try {
      cron = new CronExpression(trimmed);
    } catch (ParseException e) {
      return CommonResponse.success(
          new CronPreviewResponse(trimmed, false, e.getMessage(), List.of(), null));
    }
    TimeZone tz = TimeZone.getTimeZone(timezoneProvider.defaultZone());
    cron.setTimeZone(tz);

    List<String> next = new ArrayList<>(n);
    Date cursor = new Date();
    for (int i = 0; i < n; i++) {
      Date d = cron.getNextValidTimeAfter(cursor);
      if (d == null) break;
      next.add(d.toInstant().toString());
      cursor = d;
    }
    return CommonResponse.success(
        new CronPreviewResponse(trimmed, true, null, next, timezoneProvider.defaultZone().getId()));
  }
}
