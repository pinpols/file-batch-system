package io.github.pinpols.batch.orchestrator.application.scheduler;

/** 平台全局活跃作业的事务级准入边界。 */
public interface GlobalJobAdmission {

  /** 在当前事务内串行化并校验一个新作业是否可进入活跃态。 */
  boolean hasCapacity();

  /** 只读当前容量快照，用于 WAITING 候选预筛选；正式释放前仍须调用 {@link #hasCapacity()}。 */
  boolean hasObservedCapacity();
}
