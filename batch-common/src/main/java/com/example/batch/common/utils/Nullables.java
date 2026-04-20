package com.example.batch.common.utils;

/**
 * {@code a != null ? a : b} 的统一写法。
 *
 * <p>与 JDK {@code Objects.requireNonNullElse(a, b)} 的区别：后者要求 {@code b} 非空，否则抛 NPE；
 * 本工具允许 {@code b} 为空并直接返回，适用于"fallback to existing entity which may also be null"场景。
 *
 * <p>与 {@code Optional.ofNullable(a).orElse(b)} 的区别：本工具对 {@code a}、{@code b} 的泛型更宽松，
 * 允许 {@code a} 为 {@code T} 的子类、{@code b} 为 {@code T}（如 {@code coalesce(String, Object)}），
 * 匹配类似 {@code Map.get()} 返回 {@code Object} 的回退写法。
 */
public final class Nullables {

  private Nullables() {}

  public static <T> T coalesce(T a, T b) {
    return a != null ? a : b;
  }
}
