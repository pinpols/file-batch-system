package com.example.batch.common.persistence;

/**
 * 携带显式状态字段的实体标记接口。
 *
 * <p>实现此接口可保证状态查找在编译期安全，字段重命名将触发编译错误而非静默回退到反射路径。
 */
public interface Stateful {

  /** 返回当前实体的状态字符串。 */
  String getStatus();
}
