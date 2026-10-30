#!/usr/bin/env bash
# 用途：按 PR 并行分片运行已验证的业务集成测试并追加覆盖数据。
# 用法：bash scripts/ci/run-pr-coverage-integration.sh <a|b1|b2-workers|b2-console> [--list]。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
shard="${1:?需要指定覆盖率分片}"
mode="${2:-}"
if [[ -n "$mode" && "$mode" != "--list" ]]; then
  echo "不支持的参数: $mode" >&2
  exit 2
fi

run_group() {
  local module="$1" tests="$2"
  if [[ "$mode" == "--list" ]]; then
    printf '%s\t%s\n' "$module" "$tests"
    return
  fi
  ./mvnw -ntp test -pl "$module" "-Dtest=$tests" -DskipITs=true \
    -Djacoco.append=true -Dspotless.check.skip=true -Dpmd.skip=true -B
}

case "$shard" in
  a)
    run_group batch-orchestrator 'JobNodeDispatchIntegrationTest'
    run_group batch-orchestrator 'CompensationFailureRecoveryIntegrationTest,WaitingLaunchIdentityIntegrationTest,JobLaunchToFinishLifecycleIntegrationTest,PartitionJoinPromotionIntegrationTest,WorkflowRunResumeRecoveryIntegrationTest'
    run_group batch-orchestrator 'FileGovernanceStaleSweepIntegrationTest,WorkflowFrozenPartitionPlanIntegrationTest,CrossDayDependencyReconcilerIntegrationTest,JobTypeOutboxChainIntegrationTest,ConcurrentTaskFinishIntegrationTest,BatchDayWaitingLaunchReferenceIntegrationTest,WorkflowReplayApprovalDecisionIntegrationTest'
    run_group batch-orchestrator 'JobRetryFlowIntegrationTest,ConcurrentTaskClaimIntegrationTest,TriggerLaunchRecoveryIdentityIntegrationTest'
    run_group batch-orchestrator 'ArchiveColdStorageIntegrationTest,JobMonitoringAlertEmissionRecoveryIntegrationTest,FileErrorRecordIntegrationTest,JobTerminalOutboxIntegrationTest'
    run_group batch-orchestrator 'CountContinuityOutboxIntegrationTest,JobSlaSchedulerRecoveryIntegrationTest,FileTemplateSnapshotCaptureIntegrationTest,OutboxPublishIntegrationTest,FileArrivalGroupBacklogIntegrationTest'
    ;;
  b1)
    run_group batch-trigger 'ScheduledOccurrenceMapperIntegrationTest,QuartzMisfireRecoveryIntegrationTest,TriggerServiceIntegrationTest,ExternalEventApiKeyHttpIntegrationTest,TriggerAsyncLaunchIntegrationTest'
    run_group batch-trigger 'QuartzMisfireApplicationRestartIntegrationTest,TriggerDedupRequiresNewIntegrationTest,QuartzLaunchJobIntegrationTest,MisfireHandlerIntegrationTest,TriggerRequestSettlementFenceMapperIntegrationTest,TriggerOutboxClaimFenceMapperIntegrationTest'
    run_group batch-worker/process 'SqlTransformComputePluginIntegrationTest,ProcessStagingMapperIntegrationTest,PlatformProcessInvocationFenceIntegrationTest,ProcessStagingCrossDatabaseCleanupRecoveryIntegrationTest,ProcessStageSkipCrashResumeIntegrationTest'
    run_group batch-worker/dispatch 'DispatchChannelHealthServiceIntegrationTest'
    run_group batch-worker/dispatch 'DispatchExternalChannelIntegrationTest,FileDispatchMapperTenantReferenceIntegrationTest,DispatchSecretVersionMapperIntegrationTest'
    ;;
  b2-workers)
    run_group batch-worker/import 'ImportIngressScannerIntegrationTest,JdbcMappedImportCopyIntegrationTest,JdbcMappedImportCompensatorIntegrationTest,ImportDryRunBadRecordIntegrationTest,PreprocessStepObjectLoadIntegrationTest,LoadStepCheckpointCrashResumeIntegrationTest'
    run_group batch-worker/export 'ExportPartitionMixedFallbackIntegrationTest,GenerateDryRunSqlIntegrationTest'
    run_group batch-worker/export 'ExportKeysetRangeIT,ExportPartitionSliceIT'
    ;;
  b2-console)
    run_group batch-console-api 'ConsoleBatchDayCatchUpHttpIntegrationTest,BatchDayCatchUpOperationSchemaIntegrationTest,ConsolePendingCatchUpQueryIntegrationTest,PendingCatchUpApprovalPagingIntegrationTest,ApprovalCommandQueryIntegrationTest'
    run_group batch-console-api 'JobPushDeliveryIntegrationTest,JobPushIntakeIntegrationTest,TerminalNotificationRecoveryIntegrationTest,WorkflowTerminalNotificationIntegrationTest,JobWorkflowSlaAlertDeliveryIntegrationTest,TerminalNotificationArchiveIntegrationTest'
    run_group batch-console-api 'ConsoleFileRecordDetailSecurityIntegrationTest,FileJobAssociationQueryIntegrationTest,JobInstanceQueryIntegrationTest,ConsoleRetryScheduleQueryIntegrationTest,DeadLetterQueryIntegrationTest,FileBusinessDateQueryIntegrationTest,OpsCursorOrderIntegrationTest'
    run_group batch-console-api 'AuditLogEndToEndIntegrationTest,OperationAuditCursorHttpIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,ConsoleAiAuditServiceIntegrationTest,WorkflowConfigApprovalHttpIntegrationTest,ConsoleRealtimeReplayStoreIntegrationTest,WorkflowSensorErrorReadIntegrationTest,NotificationDeliveryTypedMapperIntegrationTest'
    run_group batch-console-api 'OutboxDeliveryTopicFilterIntegrationTest,OutboxRetryEventTypeIntegrationTest,OutboxDeliveryErrorRedactionIntegrationTest,AlertEventActionIntegrationTest,AlertEventIntegrationTest,BatchDayMapperIntegrationTest,DailyProgressMapperIntegrationTest,ExecutionCursorOrderIntegrationTest,ConsoleAtomicTaskConfigMutationIntegrationTest'
    ;;
  *)
    echo "未知的覆盖率分片: $shard" >&2
    exit 2
    ;;
esac
