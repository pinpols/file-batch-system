package io.github.pinpols.batch.worker.imports.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 到达通知的扫描结果；成功时不输出 reason，保持内部事件源的响应契约。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportEventArrivalResponse(boolean triggered, String reason) {}
