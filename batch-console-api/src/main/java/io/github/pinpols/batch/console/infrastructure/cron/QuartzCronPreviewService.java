package io.github.pinpols.batch.console.infrastructure.cron;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.response.CronPreviewResponse;
import io.github.pinpols.batch.console.domain.observability.application.CronPreviewService;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import org.quartz.CronExpression;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuartzCronPreviewService implements CronPreviewService {

  private static final int MAX_COUNT = 20;
  private static final int DEFAULT_COUNT = 3;

  private final BatchTimezoneProvider timezoneProvider;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public CronPreviewResponse preview(String expression, Integer count) {
    int n = EmptyChecks.isNull(count) ? DEFAULT_COUNT : Math.max(1, Math.min(count, MAX_COUNT));
    String trimmed = EmptyChecks.isNull(expression) ? "" : expression.trim();
    if (EmptyChecks.isEmpty(trimmed)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), "expr is required");
    }
    CronExpression cron;
    try {
      cron = new CronExpression(trimmed);
    } catch (ParseException e) {
      return new CronPreviewResponse(trimmed, false, e.getMessage(), List.of(), null);
    }
    cron.setTimeZone(TimeZone.getTimeZone(timezoneProvider.defaultZone()));

    List<String> next = new ArrayList<>(n);
    // 当前时刻走统一入口 BatchDateTimeSupport.nowInstant()(coding-conventions §20.1 禁止业务代码直接用
    // Instant.now());Quartz 的 CronExpression 只接受 java.util.Date,故在边界用 Date.from 桥接,
    // 循环内统一按 Instant 计算,结果也是 ISO-8601 Instant 字符串。
    Date cursor = Date.from(dateTimeSupport.nowInstant());
    for (int i = 0; i < n; i++) {
      Date fireTime = cron.getNextValidTimeAfter(cursor);
      if (EmptyChecks.isNull(fireTime)) break;
      next.add(fireTime.toInstant().toString());
      cursor = fireTime;
    }
    return new CronPreviewResponse(
        trimmed, true, null, next, timezoneProvider.defaultZone().getId());
  }
}
