package io.github.pinpols.batch.console.domain.observability.application;

import io.github.pinpols.batch.console.application.contract.response.CronPreviewResponse;

/** Console Cron 表达式只读预览。 */
public interface CronPreviewService {

  CronPreviewResponse preview(String expression, Integer count);
}
