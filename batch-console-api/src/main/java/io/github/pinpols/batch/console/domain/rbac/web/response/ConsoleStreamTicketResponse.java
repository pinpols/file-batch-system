package io.github.pinpols.batch.console.domain.rbac.web.response;

/**
 * SSE 一次性 ticket 响应（{@code POST /api/console/stream/ticket}）。
 *
 * <p>历史实现返回 {@code Map.of("ticket", ticket)}，单一固定键。EventSource 连接鉴权用，键不变。
 */
public record ConsoleStreamTicketResponse(String ticket) {}
