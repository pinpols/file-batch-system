package io.github.pinpols.batch.orchestrator.infrastructure.startup;

import io.github.pinpols.batch.common.mapper.InformationSchemaMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期校验：扫 {@code pg_constraint.convalidated=false} 的 CHECK / FK，全部 fail-fast。
 *
 * <p><b>背景（R7 DB 审计 P1-5）</b>：V124/V125/V126 加 CHECK / FK 时统一用 {@code NOT VALID}（避免历史异常数据 阻塞
 * migration），原计划 DBA 运维窗口逐表 {@code VALIDATE CONSTRAINT}。实际操作中常被遗忘，让 DB 长期处于
 * "新写入受约束、历史数据尚未核验"的 drift 状态。
 *
 * <p><b>守护</b>：本类与 {@link
 * io.github.pinpols.batch.orchestrator.infrastructure.archive.ArchiveSchemaDriftCheck} 同模式，启动期
 * {@link ApplicationReadyEvent} 触发，对 {@code batch} / {@code archive} 两个 schema 下所有 {@code
 * convalidated=false} 的约束断言：长度必须为 0。
 *
 * <p><b>消除路径</b>：V127 已 VALIDATE V124-V126 的 7 条约束。未来再加 {@code NOT VALID} 必须在同次发布完成
 * VALIDATE。通常直接在 migration 中执行；只有 V210/V211 这类必须使用应用密钥转换历史数据的场景，才允许由同一 Flyway
 * 事务内的 callback 先转换，随后仍由 migration 校验。本守护再次断言，禁止未校验约束进入可服务状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotValidConstraintGuard {

  private final InformationSchemaMapper informationSchemaMapper;

  @EventListener(ApplicationReadyEvent.class)
  public void checkOnStartup() {
    List<String> invalid = informationSchemaMapper.listInvalidConstraints();
    if (invalid == null || invalid.isEmpty()) {
      log.info("NOT VALID constraint guard passed: no convalidated=false constraints");
      return;
    }
    log.error(
        "NOT VALID constraint drift detected: {} constraint(s) still convalidated=false: {}",
        invalid.size(),
        invalid);
    throw new IllegalStateException("NOT VALID constraint drift: "
        + invalid.size()
        + " constraint(s) not yet VALIDATEd: "
        + invalid
        + ". Add migration ALTER TABLE ... VALIDATE CONSTRAINT ... before next deploy. "
        + "See V127 for template.");
  }
}
