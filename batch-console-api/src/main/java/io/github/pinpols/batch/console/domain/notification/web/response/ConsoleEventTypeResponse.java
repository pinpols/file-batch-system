package io.github.pinpols.batch.console.domain.notification.web.response;

/**
 * 事件目录 - 可订阅事件类型（{@code GET /api/console/event-catalog/event-types}）。
 *
 * <p>历史实现每项返回 {@code Map.of("code", "description")} 两个固定键；{@code description} 经 i18n {@code
 * MessageSource} 本地化。键与历史 wire 一字不差。
 */
public record ConsoleEventTypeResponse(String code, String description) {}
