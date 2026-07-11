package io.github.pinpols.batch.console.domain.notification.web.response;

/**
 * 事件目录 - Kafka Topic 映射（{@code GET /api/console/event-catalog/topics}）。
 *
 * <p>历史实现每项返回 {@code Map.of("name", "description")} 两个固定键；{@code name} 为真实 topic 名， {@code
 * description} 经 i18n 本地化。键与历史 wire 一字不差。
 */
public record ConsoleEventTopicResponse(String name, String description) {}
