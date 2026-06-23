package io.github.pinpols.batch.orchestrator.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 治理护栏:{@code outbox_event} 的写入(domain-event INSERT)必须**只**经 {@code OutboxDomainEventPublisher}
 * 一个入口。
 *
 * <p>背景(2026-06-10 分区/Citus 决策,见 {@code docs/design/partition-idempotency-decision.md}):分区后全局
 * {@code (tenant_id, event_key)} 唯一不可用 DB 约束表达,outbox 幂等从 {@code ON CONFLICT}(DB 强约束)降级为 {@code
 * INSERT ... WHERE NOT EXISTS}(应用层)。该弱化的**唯一可接受前提**是:所有 outbox 写入收敛到单一 choke point {@code
 * OutboxDomainEventPublisher.publish}(内部用 NOT EXISTS 去重),且 event_key 含与被锁聚合 version 绑定的判别位。
 *
 * <p>一旦有人新增"裸 {@code outboxEventMapper.insert(...)}"绕过该入口,NOT EXISTS 去重就被旁路,竞态重新引入且无 DB 回退。本测试静态扫描
 * main src,锁死这个不变量。
 */
class OutboxWriteChokePointArchTest {

  /** 允许调用 outboxEventMapper.insert( 的唯一类。 */
  private static final String ALLOWED_WRITER = "OutboxDomainEventPublisher.java";

  /** 识别 domain-event 写入调用(排除 archive 表的 archiveOutboxEventsByIds 等其他 insert)。 */
  private static final String INSERT_CALL = "outboxEventMapper.insert(";

  @Test
  void outboxEventInsertMustOnlyGoThroughDomainEventPublisher() throws IOException {
    Path mainDir = Paths.get("src/main/java");
    if (!Files.exists(mainDir)) {
      return;
    }
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainDir)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              p -> {
                String src;
                try {
                  src = Files.readString(p);
                } catch (IOException ex) {
                  violations.add(p + " — read failed");
                  return;
                }
                if (src.contains(INSERT_CALL)
                    && !p.getFileName().toString().equals(ALLOWED_WRITER)) {
                  violations.add(
                      p.getFileName()
                          + " — 直接调用 outboxEventMapper.insert(,绕过 OutboxDomainEventPublisher 的 NOT"
                          + " EXISTS 去重(分区下 outbox 幂等承重在此 choke point,见"
                          + " partition-idempotency-decision.md)");
                }
              });
    }
    assertThat(violations)
        .as("outbox_event 写入必须只经 OutboxDomainEventPublisher(NOT EXISTS 去重 choke point)")
        .isEmpty();
  }
}
