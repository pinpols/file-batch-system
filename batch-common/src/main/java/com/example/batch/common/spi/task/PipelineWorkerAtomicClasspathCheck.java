package com.example.batch.common.spi.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ADR-029 pipeline worker classpath 隔离守护:启动期 fail-fast 拦截 {@code batch-worker-atomic} 内的
 * dual-use(shell/sql/stored-proc/http)executor 类被误打进 4 个文件 pipeline worker 的 classpath。
 *
 * <p><b>背景</b>:ADR-029 把 atomic SPI(dual-use RCE 级 executor)独立成 {@code batch-worker-atomic} 模块,跟 4
 * 个文件 pipeline worker(import / export / process / dispatch)物理隔离。若任何 pipeline worker 的 {@code
 * pom.xml} 误引了 {@code batch-worker-atomic} 依赖,Spring component-scan 会激活 {@link
 * org.springframework.stereotype.Component} 修饰的 atomic executor bean,等于把 RCE 接口接入 pipeline worker
 * 镜像 — 与隔离初衷背离,且 pipeline worker 通常持业务库高权限连接,组合后果更严重。
 *
 * <p><b>守护逻辑</b>:{@link ApplicationReadyEvent} 触发,用 {@link Class#forName(String, boolean,
 * ClassLoader)} 探针 {@code com.example.batch.worker.atomic.sql.SqlTaskExecutor};命中 → 抛 {@link
 * IllegalStateException} fail-fast。SqlTaskExecutor 作 canary 已足够 —— 4 个 dual-use executor 同模块,引入是
 * 模块级二选一,不会出现 "只引部分 class" 的情况。
 *
 * <p><b>启用</b>:本 bean 通过 {@link Component} + {@link ConditionalOnProperty} 注册,默认开;只需被 pipeline
 * worker 的 component-scan 覆盖(4 个 pipeline worker 已 {@code scanBasePackages = "com.example.batch"})。
 * 单测可通过 {@code batch.worker.atomic.isolation-check.enabled=false} 关闭。
 *
 * <p><b>为何放在 batch-common</b>:atomic worker 自身依赖 batch-common,把检查放 atomic 模块会让 atomic worker
 * 启动时拦自己; 放 batch-common 让 4 个 pipeline worker 自动复用,且 atomic worker 的 component-scan 也会扫到 —— 但探针类
 * SqlTaskExecutor 本身就在 atomic 模块内,atomic worker 启动时**必定**命中并 fail-fast。因此本组件在 atomic worker 上必须关:
 * {@code batch-worker-atomic} 的 {@code application.yml} 已置 {@code
 * batch.worker.atomic.isolation-check.enabled=false}(见同 PR 配置)。
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "batch.worker.atomic.isolation-check",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PipelineWorkerAtomicClasspathCheck {

  /** atomic SPI canary 类全限定名 —— 命中即视为整组 dual-use executor 都在 classpath。 */
  static final String CANARY_CLASS = "com.example.batch.worker.atomic.sql.SqlTaskExecutor";

  @EventListener(ApplicationReadyEvent.class)
  public void verifyClasspathIsolation() {
    if (isCanaryPresent()) {
      throw new IllegalStateException(
          "ADR-029 violation: atomic SPI executor ("
              + CANARY_CLASS
              + ") detected on pipeline worker classpath. Dual-use (shell/sql/stored-proc/http) "
              + "executors must run only in batch-worker-atomic. Check pom.xml for stray "
              + "batch-worker-atomic dependency. To disable this guard (atomic worker itself), set "
              + "batch.worker.atomic.isolation-check.enabled=false.");
    }
    log.debug(
        "ADR-029 classpath isolation OK: no atomic SPI executor in pipeline worker classpath");
  }

  /**
   * 探针 canary 类是否在 classpath。protected 便于单测以子类 override 形式注入「命中 / 未命中」两态,而无须 mockStatic {@link
   * Class#forName(String, boolean, ClassLoader)}(后者跨 JDK / mockito-inline 版本行为不一致)。
   */
  protected boolean isCanaryPresent() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = PipelineWorkerAtomicClasspathCheck.class.getClassLoader();
    }
    try {
      Class.forName(CANARY_CLASS, false, cl);
      return true;
    } catch (ClassNotFoundException missing) {
      return false;
    }
  }
}
