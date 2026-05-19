package com.example.batch.console.web.response;

import java.util.List;

/**
 * Cron 预览响应。
 *
 * @param expr 回显的输入表达式(去首尾空格)
 * @param valid 解析是否成功
 * @param error valid=false 时的解析错误说明,valid=true 时为 null
 * @param nextRuns ISO-8601 UTC 时间字符串列表,按时间升序;无下次触发(如固定日期已过)时为空
 * @param timezone 用于计算的时区 ID(IANA),如 "Asia/Shanghai";valid=false 时为 null
 */
public record CronPreviewResponse(
    String expr, boolean valid, String error, List<String> nextRuns, String timezone) {}
