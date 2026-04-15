package com.example.batch.orchestrator.domain.statemachine;

/**
 * 向后兼容别名——实际接口已移至 {@link com.example.batch.common.persistence.Stateful}。
 *
 * <p>orchestrator 内部的实体和状态机可继续使用此包路径，编译器通过继承关系自动桥接。
 */
public interface Stateful extends com.example.batch.common.persistence.Stateful {}
