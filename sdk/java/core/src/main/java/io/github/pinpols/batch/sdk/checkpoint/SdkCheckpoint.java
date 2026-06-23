package io.github.pinpols.batch.sdk.checkpoint;

import java.util.Optional;

/**
 * ADR-037 决策一 — 断点续跑协议。
 *
 * <p>SDK 只定义「读 / 写断点」两个动作,<b>不规定存到哪</b>:可以是租户自己的控制表、KV、对象存储。维持 ADR-035 §7 边界 ——
 * 平台不感知租户库,断点持久化完全在租户进程内。
 *
 * <p><b>强约束(见 {@link io.github.pinpols.batch.sdk.task.SdkTaskContext#commit}):{@link #save}
 * 必须与业务数据在同一个事务边界内提交</b>, 二者要么都成功、要么都回滚。否则崩溃后断点与业务数据会撕裂:业务提交了而断点没更新 → 重跑重复处理;反之 → 丢数据。SDK 提供的 JDBC
 * 默认实现 ({@code JdbcSdkCheckpoint})在<b>同一个 {@link java.sql.Connection}</b> 里 {@code update 断点行} 再
 * {@code connection.commit()}, 把业务写与断点写合成一个事务。租户用别的存储时必须自行保证同等原子性,文档与 code review 卡这一点。
 *
 * <p>默认实现:
 *
 * <ul>
 *   <li>{@link InMemorySdkCheckpoint} —— 进程内 Map,供测试 / 示例;<b>无持久化,不要用于生产</b>。
 *   <li>{@code JdbcSdkCheckpoint} —— 同 Connection 同事务的 JDBC 示例骨架,租户接自家 business {@link
 *       javax.sql.DataSource} 后可用。
 * </ul>
 */
public interface SdkCheckpoint {

  /** 启动时读回上次断点;首次运行返回 {@link Optional#empty()}。 */
  Optional<SdkCheckpointState> load(String taskId);

  /**
   * 保存断点。
   *
   * <p><b>必须与业务数据同事务</b>(见接口注释)。实现若涉及 I/O,抛出运行时异常即可,模板会把整批回滚并落 fail 终态。
   */
  void save(String taskId, SdkCheckpointState state);
}
