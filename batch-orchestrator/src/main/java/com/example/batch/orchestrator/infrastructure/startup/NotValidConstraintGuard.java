package com.example.batch.orchestrator.infrastructure.startup;

import com.example.batch.common.mapper.InformationSchemaMapper;
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
 * migration），原计划 DBA 运维窗口逐表 {@code VALIDATE CONSTRAINT}。实际操作中常被遗忘，让 DB 长期处于 "约束已加但未校验存量"的 drift
 * 状态：新插入行能违反约束直到 VALIDATE 跑完。
 *
 * <p><b>守护</b>：本类与 {@link
 * com.example.batch.orchestrator.infrastructure.archive.ArchiveSchemaDriftCheck} 同模式，启动期 {@link
 * ApplicationReadyEvent} 触发，对 {@code batch} / {@code archive} 两个 schema 下所有 {@code
 * convalidated=false} 的约束断言：长度必须为 0。
 *
 * <p><b>消除路径</b>：V127 已 VALIDATE V124-V126 的 7 条约束。未来再加 {@code NOT VALID} 必须同 sprint 内 补 VALIDATE
 * migration（V127 同模板），否则下次重启即 fail-fast。
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
    throw new IllegalStateException(
        "NOT VALID constraint drift: "
            + invalid.size()
            + " constraint(s) not yet VALIDATEd: "
            + invalid
            + ". Add migration ALTER TABLE ... VALIDATE CONSTRAINT ... before next deploy. "
            + "See V127 for template.");
  }
}
