package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.WebhookRelayProperties;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationArchiveMapper;
import io.github.pinpols.batch.console.domain.notification.mapper.TerminalNotificationArchiveMapper.Family;
import io.github.pinpols.batch.console.domain.notification.mapper.WorkflowTerminalNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.view.TerminalNotificationArchiveCandidate;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 复用既有 relay 调度和租户审计保留策略，整族迁移已闭合事实；不永久删除证据。 */
@Service
public class TerminalNotificationArchiveService {
  private static final List<Family> COPY_ORDER =
      List.of(Family.RECEIPT, Family.WEBHOOK, Family.RULE, Family.PUSH, Family.AUDIT);
  private static final List<Family> DELETE_ORDER =
      List.of(Family.AUDIT, Family.PUSH, Family.RULE, Family.WEBHOOK, Family.RECEIPT);

  private final TerminalNotificationArchiveMapper archive;
  private final WorkflowTerminalNotificationMapper receipts;
  private final WebhookRelayProperties properties;
  private final TransactionTemplate transactions;
  private String afterTenant = "";

  public TerminalNotificationArchiveService(
      TerminalNotificationArchiveMapper archive,
      WorkflowTerminalNotificationMapper receipts,
      WebhookRelayProperties properties,
      @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
    this.archive = archive;
    this.receipts = receipts;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** 每轮受既有批大小限制；每个事实独立提交，失败保留整族热数据。 */
  public synchronized int poll(BooleanSupplier stopping) {
    if (stopped(stopping)) {
      return 0;
    }
    int moved = 0;
    int remaining = Math.max(1, properties.getBatchSize());
    int tenantLimit = Math.min(100, remaining);
    List<String> tenants = archive.enabledTenants(afterTenant, tenantLimit);
    if (EmptyChecks.isEmpty(Objects.requireNonNull(tenants))) {
      afterTenant = "";
      tenants = archive.enabledTenants(afterTenant, tenantLimit);
    }
    for (String tenantId : tenants) {
      if (stopped(stopping) || remaining <= 0) {
        break;
      }
      afterTenant = tenantId;
      int candidateLimit = remaining;
      // receipt/cold 表强制 RLS；与现有 Push 扫描一致，逐租户绑定后再读候选。
      List<TerminalNotificationArchiveCandidate> candidates =
          inTenant(tenantId, () -> archive.candidates(tenantId, candidateLimit));
      for (TerminalNotificationArchiveCandidate candidate : candidates) {
        if (stopped(stopping) || remaining <= 0) {
          return moved;
        }
        remaining--;
        Boolean archived = inTenant(tenantId, () -> archiveFamily(candidate));
        if (Boolean.TRUE.equals(archived)) {
          moved++;
        }
      }
    }
    return moved;
  }

  private static boolean stopped(BooleanSupplier stopping) {
    return stopping.getAsBoolean() || Thread.currentThread().isInterrupted();
  }

  private <T> T inTenant(String tenantId, Supplier<T> action) {
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> transactions.execute(status -> {
          receipts.bindTenant(tenantId);
          return action.get();
        }));
  }

  private boolean archiveFamily(TerminalNotificationArchiveCandidate candidate) {
    // 锁顺序与 Kafka 接纳一致；锁之后的新语句才能看到已提交的冷热迁移结果。
    receipts.lockReceipt(candidate.tenantId(), candidate.eventKey());
    Boolean cleanupEnabled = archive.lockReadyReceipt(candidate);
    if (EmptyChecks.isNull(cleanupEnabled)) {
      return false;
    }
    for (Family family : COPY_ORDER) {
      archive.copyFamily(candidate, family);
      long mismatches = archive.mismatches(candidate, family);
      if (mismatches != 0) {
        throw new IllegalStateException(
            "terminal notification archive differs from source: " + family);
      }
    }
    if (cleanupEnabled) {
      for (Family family : DELETE_ORDER) {
        archive.deleteFamily(candidate, family);
      }
    }
    return true;
  }
}
