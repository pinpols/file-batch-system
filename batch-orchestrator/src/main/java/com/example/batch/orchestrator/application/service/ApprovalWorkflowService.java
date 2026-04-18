package com.example.batch.orchestrator.application.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 审批工作流服务接口，定义人工审批节点的完整生命周期操作：提交、审批、拒绝、标记已执行和查询。
 *
 * <p>接口内通过嵌套 record 将多字段参数封装为语义明确的值对象（{@link ApprovalSubmitCommand}、
 * {@link ApprovalRecord} 等），既遵守方法参数不超过 6 个的编码规范，又保持调用侧可读性。
 * {@link ApprovalSubmitCommand} 采用静态工厂方法构造，隐藏内部聚合结构；
 * {@link ApprovalRecord} 使用 {@code @JsonProperty} 注解直接支持 HTTP 响应序列化。
 *
 * <p>所有操作均以租户 ID 为隔离边界，审批编号（approvalNo）在租户内唯一标识一次审批流程。
 */
public interface ApprovalWorkflowService {

  String submit(ApprovalSubmitCommand command);

  ApprovalRecord approve(
      String tenantId, String approvalNo, String approverId, String approvalReason);

  ApprovalRecord reject(
      String tenantId, String approvalNo, String approverId, String approvalReason);

  ApprovalRecord markExecuted(String tenantId, String approvalNo);

  ApprovalRecord get(String tenantId, String approvalNo);

  record ApprovalContext(String tenantId) {}

  record ApprovalIdentity(ApprovalContext context, String approvalNo) {}

  record ApprovalTarget(
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      String payloadJson) {}

  record ApprovalSource(
      String requesterId,
      String sourceTraceId,
      String sourceIdempotencyKey,
      String approvalReason) {}

  final class ApprovalSubmitCommand {

    private final ApprovalContext context;
    private final ApprovalTarget target;
    private final ApprovalSource source;

    private ApprovalSubmitCommand(
        ApprovalContext context, ApprovalTarget target, ApprovalSource source) {
      this.context = context;
      this.target = target;
      this.source = source;
    }

    public static ApprovalSubmitCommand of(
        String tenantId, ApprovalTarget target, ApprovalSource source) {
      return new ApprovalSubmitCommand(new ApprovalContext(tenantId), target, source);
    }

    public static ApprovalSubmitCommand of(
        ApprovalContext context, ApprovalTarget target, ApprovalSource source) {
      return new ApprovalSubmitCommand(context, target, source);
    }

    public String tenantId() {
      return context.tenantId();
    }

    public String approvalType() {
      return target.approvalType();
    }

    public String actionType() {
      return target.actionType();
    }

    public String targetType() {
      return target.targetType();
    }

    public String targetId() {
      return target.targetId();
    }

    public String payloadJson() {
      return target.payloadJson();
    }

    public String requesterId() {
      return source.requesterId();
    }

    public String sourceTraceId() {
      return source.sourceTraceId();
    }

    public String sourceIdempotencyKey() {
      return source.sourceIdempotencyKey();
    }

    public String approvalReason() {
      return source.approvalReason();
    }
  }

  record ApprovalOutcome(
      String approvalStatus, String approverId, String rejectionReason, String approvalReason) {}

  final class ApprovalRecord {

    private final ApprovalIdentity identity;
    private final ApprovalTarget target;
    private final ApprovalOutcome outcome;
    private final ApprovalSource source;

    private ApprovalRecord(
        ApprovalIdentity identity,
        ApprovalTarget target,
        ApprovalOutcome outcome,
        ApprovalSource source) {
      this.identity = identity;
      this.target = target;
      this.outcome = outcome;
      this.source = source;
    }

    public static ApprovalRecord of(
        String tenantId,
        String approvalNo,
        ApprovalTarget target,
        ApprovalOutcome outcome,
        ApprovalSource source) {
      return new ApprovalRecord(
          new ApprovalIdentity(new ApprovalContext(tenantId), approvalNo), target, outcome, source);
    }

    public static ApprovalRecord of(
        ApprovalIdentity identity,
        ApprovalTarget target,
        ApprovalOutcome outcome,
        ApprovalSource source) {
      return new ApprovalRecord(identity, target, outcome, source);
    }

    @JsonProperty("tenantId")
    public String tenantId() {
      return identity.context().tenantId();
    }

    @JsonProperty("approvalNo")
    public String approvalNo() {
      return identity.approvalNo();
    }

    @JsonProperty("approvalType")
    public String approvalType() {
      return target.approvalType();
    }

    @JsonProperty("actionType")
    public String actionType() {
      return target.actionType();
    }

    @JsonProperty("targetType")
    public String targetType() {
      return target.targetType();
    }

    @JsonProperty("targetId")
    public String targetId() {
      return target.targetId();
    }

    @JsonProperty("payloadJson")
    public String payloadJson() {
      return target.payloadJson();
    }

    @JsonProperty("approvalStatus")
    public String approvalStatus() {
      return outcome.approvalStatus();
    }

    @JsonProperty("requesterId")
    public String requesterId() {
      return source.requesterId();
    }

    @JsonProperty("approverId")
    public String approverId() {
      return outcome.approverId();
    }

    @JsonProperty("rejectionReason")
    public String rejectionReason() {
      return outcome.rejectionReason();
    }

    @JsonProperty("approvalReason")
    public String approvalReason() {
      return outcome.approvalReason();
    }

    @JsonProperty("sourceTraceId")
    public String sourceTraceId() {
      return source.sourceTraceId();
    }

    @JsonProperty("sourceIdempotencyKey")
    public String sourceIdempotencyKey() {
      return source.sourceIdempotencyKey();
    }
  }
}
