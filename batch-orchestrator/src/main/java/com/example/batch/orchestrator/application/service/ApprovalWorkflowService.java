package com.example.batch.orchestrator.application.service;

import com.fasterxml.jackson.annotation.JsonProperty;

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
