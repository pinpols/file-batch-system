package client

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/sdk/go/protocol"
)

func TestDryRunCapabilityRequiresExplicitOptIn(t *testing.T) {
	regular := (Config{CapabilityTags: []string{"PROCESS"}}).withDefaults()
	safe := (Config{CapabilityTags: []string{"PROCESS"}, DryRunSafe: true}).withDefaults()

	if len(regular.CapabilityTags) != 1 {
		t.Fatalf("regular capabilities = %v", regular.CapabilityTags)
	}
	if len(safe.CapabilityTags) != 2 || safe.CapabilityTags[1] != DryRunSafeCapability {
		t.Fatalf("safe capabilities = %v", safe.CapabilityTags)
	}
}

func testConfig() Config {
	return Config{
		WorkerCode:         "w1",
		TenantID:           "t1",
		MaxConcurrentTasks: 4,
		HeartbeatInterval:  time.Hour, // never fires during the test
		LeaseRenewInterval: time.Hour,
		StopTimeout:        time.Second,
	}
}

func dispatchRecord(taskID string) Record {
	return Record{
		Topic: "batch.task.dispatch.t1.0",
		Value: []byte(`{"taskId":"` + taskID + `","tenantId":"t1","schemaVersion":"v1","idempotencyKey":"idem-` + taskID + `"}`),
	}
}

type duplicateStartPlatform struct {
	*FakePlatform
	mu       sync.Mutex
	starts   int
	rejected chan struct{}
	renewed  chan struct{}
}

func (p *duplicateStartPlatform) Renew(_ context.Context, _ string, request RenewRequest) (RenewResult, error) {
	if request.Details["executionStarted"] == true {
		p.mu.Lock()
		defer p.mu.Unlock()
		p.starts++
		if p.starts > 1 {
			close(p.rejected)
			return RenewResult{Revoked: true}, nil
		}
		return RenewResult{}, nil
	}
	select {
	case p.renewed <- struct{}{}:
	default:
	}
	cancel := true
	return RenewResult{RenewResponse: protocol.RenewResponse{CancelRequested: &cancel}}, nil
}

func TestDuplicateDeliveryPreservesOriginalLeaseAndWithholdsOffset(t *testing.T) {
	platform := &duplicateStartPlatform{
		FakePlatform: NewFakePlatform(), rejected: make(chan struct{}), renewed: make(chan struct{}, 1),
	}
	platform.ClaimResp = ClaimResult{PartitionInvocationID: "inv-current"}
	started := make(chan *TaskContext, 1)
	release := make(chan struct{})
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		started <- ctx
		<-release
		return Success(nil, "ok")
	})
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1"), dispatchRecord("task-1")})
	worker := NewWorker(testConfig(), platform, consumer, handler, nil, quietLogger())
	if err := worker.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	var releaseOnce sync.Once
	defer func() { releaseOnce.Do(func() { close(release) }); worker.Stop(time.Second) }()
	waitFor(t, time.Second, func() bool { return consumer.commits()+consumer.withholds() == 2 })
	var task *TaskContext
	select {
	case task = <-started:
	case <-time.After(time.Second):
		t.Fatal("首次业务未开始")
	}
	platform.mu.Lock()
	starts := platform.starts
	platform.mu.Unlock()
	if starts > 1 {
		<-platform.rejected
		waitFor(t, time.Second, func() bool { return worker.InFlight() == 0 })
	}
	if consumer.commits() != 1 || consumer.withholds() != 1 {
		t.Errorf("首次和重复消息的 offset：commit=%d withhold=%d", consumer.commits(), consumer.withholds())
	}
	if worker.InFlight() != 1 {
		t.Errorf("首次执行仍在进行，租约登记不应被重复消息删除")
	}
	worker.lease.RenewOnce(context.Background())
	select {
	case <-platform.renewed:
	case <-time.After(time.Second):
		t.Error("原执行未获得后续续租")
	}
	if !task.Cancellation.IsCancellationRequested() {
		t.Error("原执行未收到续租返回的取消信号")
	}
	releaseOnce.Do(func() { close(release) })
	waitFor(t, time.Second, func() bool { return worker.InFlight() == 0 })
}

type delayedClaimPlatform struct {
	*FakePlatform
	mu      sync.Mutex
	count   int
	started chan struct{}
	release chan struct{}
}

func (p *delayedClaimPlatform) Claim(_ context.Context, _ string, _ string, _ ClaimRequest) (ClaimResult, error) {
	p.mu.Lock()
	p.count++
	attempt := p.count
	p.mu.Unlock()
	if attempt == 1 {
		close(p.started)
		<-p.release
		return ClaimResult{}, errors.New("claim unavailable")
	}
	return ClaimResult{PartitionInvocationID: "inv-current"}, nil
}

func TestDuplicateWhileClaimPendingIsWithheldAndReservationIsReleased(t *testing.T) {
	platform := &delayedClaimPlatform{
		FakePlatform: NewFakePlatform(), started: make(chan struct{}), release: make(chan struct{}),
	}
	worker := NewWorker(testConfig(), platform, NewFakeConsumer(nil),
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "ok") }), nil, quietLogger())
	message := TaskDispatchMessage{TaskID: "task-1", TenantID: "t1"}
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(platform.release) })
	first := make(chan bool, 1)
	go func() { first <- worker.dispatch(context.Background(), message) }()
	select {
	case <-platform.started:
	case <-time.After(time.Second):
		t.Fatal("首次认领未开始")
	}
	if worker.dispatch(context.Background(), message) {
		t.Error("未完成的重复认领不应提交 offset")
	}
	releaseOnce.Do(func() { close(platform.release) })
	if <-first {
		t.Error("认领失败不应提交 offset")
	}
	if !worker.dispatch(context.Background(), message) {
		t.Error("认领失败后应允许后续投递重新认领")
	}
	worker.taskWg.Wait()
	platform.mu.Lock()
	defer platform.mu.Unlock()
	if platform.count != 2 {
		t.Errorf("认领请求数=%d，重复投递不应发起额外认领", platform.count)
	}
}

type stopDuringClaimPlatform struct {
	*FakePlatform
	started chan struct{}
	release chan struct{}
}

func (p *stopDuringClaimPlatform) Claim(_ context.Context, _ string, _ string, _ ClaimRequest) (ClaimResult, error) {
	close(p.started)
	<-p.release
	return ClaimResult{PartitionInvocationID: "inv-current"}, nil
}

func TestStopDuringClaimDoesNotStartBusinessOrCommit(t *testing.T) {
	platform := &stopDuringClaimPlatform{
		FakePlatform: NewFakePlatform(), started: make(chan struct{}), release: make(chan struct{}),
	}
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	executed := make(chan struct{}, 1)
	worker := NewWorker(testConfig(), platform, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { executed <- struct{}{}; return Success(nil, "ok") }), nil, quietLogger())
	if err := worker.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(platform.release) })
	select {
	case <-platform.started:
	case <-time.After(time.Second):
		t.Fatal("认领未开始")
	}
	stopped := make(chan struct{})
	go func() { worker.Stop(time.Second); close(stopped) }()
	waitFor(t, time.Second, func() bool { return consumer.wakeups() > 0 })
	select {
	case <-worker.rootCtx.Done():
	case <-time.After(30 * time.Millisecond):
	}
	releaseOnce.Do(func() { close(platform.release) })
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("停机未完成")
	}
	if consumer.commits() != 0 {
		t.Errorf("停机期间的认领不应提交 offset，实际提交 %d", consumer.commits())
	}
	select {
	case <-executed:
		t.Error("开始停机后不能再启动业务")
	default:
	}
}

type delayedRenewPlatform struct {
	*FakePlatform
	mu      sync.Mutex
	count   int
	started chan struct{}
	release chan struct{}
	renewed chan struct{}
}

func (p *delayedRenewPlatform) Renew(_ context.Context, _ string, request RenewRequest) (RenewResult, error) {
	if request.Details["executionStarted"] == true {
		return RenewResult{}, nil
	}
	p.mu.Lock()
	p.count++
	count := p.count
	p.mu.Unlock()
	if count == 1 {
		close(p.started)
		<-p.release
		return RenewResult{Revoked: true}, nil
	}
	select {
	case p.renewed <- struct{}{}:
	default:
	}
	return RenewResult{}, nil
}

func TestLateLeaseRejectionDoesNotRemoveNextExecution(t *testing.T) {
	platform := &delayedRenewPlatform{
		FakePlatform: NewFakePlatform(), started: make(chan struct{}), release: make(chan struct{}), renewed: make(chan struct{}, 1),
	}
	platform.ClaimResp = ClaimResult{PartitionInvocationID: "inv-current"}
	started := make(chan *TaskContext, 2)
	releaseFirst := make(chan struct{})
	releaseSecond := make(chan struct{})
	var executionMu sync.Mutex
	executions := 0
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		executionMu.Lock()
		executions++
		index := executions
		executionMu.Unlock()
		started <- ctx
		if index == 1 {
			<-releaseFirst
		} else {
			<-releaseSecond
		}
		return Success(nil, "ok")
	})
	worker := NewWorker(testConfig(), platform, NewFakeConsumer(nil), handler, nil, quietLogger())
	message := TaskDispatchMessage{TaskID: "task-1", TenantID: "t1"}
	var firstOnce, secondOnce, renewOnce sync.Once
	defer firstOnce.Do(func() { close(releaseFirst) })
	defer secondOnce.Do(func() { close(releaseSecond) })
	defer renewOnce.Do(func() { close(platform.release) })
	if !worker.dispatch(context.Background(), message) {
		t.Fatal("首次任务未认领")
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("首次执行未开始")
	}
	renewFinished := make(chan struct{})
	go func() { worker.lease.RenewOnce(context.Background()); close(renewFinished) }()
	select {
	case <-platform.started:
	case <-time.After(time.Second):
		t.Fatal("旧租约续期未开始")
	}
	firstOnce.Do(func() { close(releaseFirst) })
	worker.taskWg.Wait()
	if !worker.dispatch(context.Background(), message) {
		t.Fatal("后续任务未认领")
	}
	var next *TaskContext
	select {
	case next = <-started:
	case <-time.After(time.Second):
		t.Fatal("后续执行未开始")
	}
	renewOnce.Do(func() { close(platform.release) })
	select {
	case <-renewFinished:
	case <-time.After(time.Second):
		t.Fatal("旧续期未结束")
	}
	if worker.InFlight() != 1 {
		t.Error("旧续租拒绝不应移除后续执行的登记")
	}
	if next.Cancellation.IsCancellationRequested() {
		t.Error("旧续租拒绝不应取消后续执行")
	}
	worker.lease.RenewOnce(context.Background())
	select {
	case <-platform.renewed:
	case <-time.After(time.Second):
		t.Error("后续执行未获得续租")
	}
	secondOnce.Do(func() { close(releaseSecond) })
	worker.taskWg.Wait()
}

// Start -> consume one task -> handler runs -> report SUCCESS. Then Stop ordering:
// wakeup, drain, deactivate.
func TestWorker_StartConsumeReportStop(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimResp = ClaimResult{
		Payload:               `{"batchSize":20,"crossDay":{"prior":{"resultVersionId":8}}}`,
		EffectiveConfig:       map[string]any{"batchSize": 10},
		TraceID:               "tr-1",
		PartitionInvocationID: "inv-from-claim",
	}

	var ran bool
	var mu sync.Mutex
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		mu.Lock()
		ran = true
		mu.Unlock()
		if ctx.TraceID != "tr-1" {
			t.Errorf("expected traceId tr-1, got %q", ctx.TraceID)
		}
		if ctx.Parameters["batchSize"] != float64(20) || ctx.EffectiveConfig["batchSize"] != float64(20) {
			t.Errorf("claim payload must be authoritative, got parameters=%v effectiveConfig=%v", ctx.Parameters, ctx.EffectiveConfig)
		}
		return Success(map[string]any{"ok": true}, "done")
	})

	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer, handler, nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	if got := fp.RegisterCalls[0].MaxConcurrent; got != 4 {
		t.Fatalf("register maxConcurrent = %d, want 4", got)
	}

	// wait for the report to land.
	waitFor(t, time.Second, func() bool {
		_, ok := fp.ReportFor("task-1")
		return ok
	})

	mu.Lock()
	gotRan := ran
	mu.Unlock()
	if !gotRan {
		t.Fatalf("handler did not run")
	}
	report, _ := fp.ReportFor("task-1")
	if report.Result.ErrorCode != protocol.ErrorCodeSuccess {
		t.Fatalf("expected SUCCESS report, got %s", report.Result.ErrorCode)
	}
	if report.Result.PartitionInvocationID != "inv-from-claim" {
		t.Fatalf("report must reuse claim partitionInvocationId, got %q", report.Result.PartitionInvocationID)
	}
	// fixture 24: report mints a FRESH go-<uuid> key, never the Kafka delivery
	// key and never a fixed report-{taskId} — so a redelivered task's report
	// can't replay a stale outcome from the platform idempotency store.
	if report.IdempotencyKey == "idem-task-1" || !strings.HasPrefix(report.IdempotencyKey, "go-") {
		t.Fatalf("expected freshly-minted go-<uuid> report key (not the Kafka key), got %q", report.IdempotencyKey)
	}

	w.Stop(time.Second)

	if consumer.wakeups() == 0 {
		t.Fatalf("Stop must wakeup the consumer")
	}
	if !consumer.closed() {
		t.Fatalf("Stop must close the consumer")
	}
	if len(fp.DeactivateCalls) != 1 {
		t.Fatalf("Stop must deactivate exactly once, got %d", len(fp.DeactivateCalls))
	}
	if w.FSM().State() != StateDraining {
		t.Fatalf("expected DRAINING state after stop, got %s", w.FSM().State())
	}
}

func TestWorker_ClaimPayloadReplacesEffectiveConfig(t *testing.T) {
	tests := []struct {
		name          string
		payload       string
		wantParams    map[string]any
		wantErrorCode protocol.ErrorCode
	}{
		{
			name:       "payload replaces legacy keys",
			payload:    `{"limit":5}`,
			wantParams: map[string]any{"limit": float64(5)},
		},
		{
			name:       "empty object clears legacy keys",
			payload:    `{}`,
			wantParams: map[string]any{},
		},
		{
			name:          "non-object payload is rejected without handler execution",
			payload:       `[]`,
			wantErrorCode: protocol.ErrorCodeExecutionFailed,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			fp := NewFakePlatform()
			fp.ClaimResp = ClaimResult{
				Payload:         test.payload,
				EffectiveConfig: map[string]any{"legacyOnly": "stale", "limit": 99},
				TraceID:         "tr-payload",
			}
			handledParams := make(chan map[string]any, 1)
			consumer := NewFakeConsumer([]Record{dispatchRecord("task-payload")})
			handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
				handledParams <- ctx.Parameters
				return Success(nil, "done")
			})
			worker := NewWorker(testConfig(), fp, consumer, handler, nil, quietLogger())
			if err := worker.Start(context.Background()); err != nil {
				t.Fatalf("start: %v", err)
			}
			defer worker.Stop(time.Second)

			waitFor(t, time.Second, func() bool {
				_, ok := fp.ReportFor("task-payload")
				return ok
			})
			report, _ := fp.ReportFor("task-payload")
			if test.wantErrorCode != "" {
				if report.Result.ErrorCode != test.wantErrorCode {
					t.Fatalf("error code = %q, want %q", report.Result.ErrorCode, test.wantErrorCode)
				}
				select {
				case params := <-handledParams:
					t.Fatalf("handler must not execute for invalid payload, got %v", params)
				default:
				}
			} else {
				if report.Result.ErrorCode != protocol.ErrorCodeSuccess {
					t.Fatalf("expected SUCCESS report, got %s", report.Result.ErrorCode)
				}
				select {
				case params := <-handledParams:
					if !reflect.DeepEqual(params, test.wantParams) {
						t.Fatalf("handler parameters = %v, want %v", params, test.wantParams)
					}
				case <-time.After(time.Second):
					t.Fatal("handler did not receive claim payload")
				}
			}

			if !reflect.DeepEqual(
				fp.ClaimResp.EffectiveConfig,
				map[string]any{"legacyOnly": "stale", "limit": 99},
			) {
				t.Fatalf("claim effective config mutated: %v", fp.ClaimResp.EffectiveConfig)
			}
		})
	}
}

// Register-path sensitive credential -> Start fails fast, no register call, no loops.
func TestWorker_StartFailsOnSensitiveRegister(t *testing.T) {
	fp := NewFakePlatform()
	cfg := testConfig()
	cfg.RegisterAttributes = map[string]any{"secret": "leaked"}
	w := NewWorker(cfg, fp, NewFakeConsumer(),
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())

	err := w.Start(context.Background())
	if err == nil {
		t.Fatalf("expected Start to fail fast on sensitive register attribute")
	}
	if len(fp.RegisterCalls) != 0 {
		t.Fatalf("must not call Register when sensitive scan fails, got %d", len(fp.RegisterCalls))
	}
}

// Foreign-tenant message is dropped and never claimed/executed.
func TestWorker_ForeignTenantNotExecuted(t *testing.T) {
	fp := NewFakePlatform()
	foreign := Record{Topic: "x", Value: []byte(`{"taskId":"f1","tenantId":"OTHER","schemaVersion":"v1"}`)}
	consumer := NewFakeConsumer([]Record{foreign})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult {
			t.Error("handler must not run for foreign tenant")
			return Success(nil, "")
		}),
		nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	w.Stop(time.Second)

	if len(fp.ClaimCalls) != 0 {
		t.Fatalf("foreign-tenant message must not be claimed, got %d claims", len(fp.ClaimCalls))
	}
}

// Undecodable poison record is commit-skipped (offset committed, handler never
// runs) so one corrupt message cannot head-of-line block the partition.
// fixture 30 / parity §4.5.
func TestWorker_DecodeErrorCommitSkips(t *testing.T) {
	fp := NewFakePlatform()
	poison := Record{Topic: "x", Value: []byte(`not-json-garbage`)}
	consumer := NewFakeConsumer([]Record{poison})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult {
			t.Error("handler must not run for an undecodable record")
			return Success(nil, "")
		}),
		nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	w.Stop(time.Second)

	if len(fp.ClaimCalls) != 0 {
		t.Fatalf("poison record must not be claimed, got %d claims", len(fp.ClaimCalls))
	}
	if consumer.commits() == 0 {
		t.Fatalf("poison record must commit-skip (advance offset), got 0 commits")
	}
}

// SIGTERM path: cancelling the signal.NotifyContext parent triggers Stop(30s).
func TestWorker_SignalTriggersStop(t *testing.T) {
	fp := NewFakePlatform()
	consumer := NewFakeConsumer()
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- w.RunUntilSignal(ctx) }()

	// give RunUntilSignal time to Start.
	waitFor(t, time.Second, func() bool { r, _, _, _, _, _ := fp.Counts(); return r == 1 })

	// simulate SIGTERM by cancelling the parent context.
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("RunUntilSignal returned error: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("RunUntilSignal did not return after signal")
	}
	if len(fp.DeactivateCalls) != 1 {
		t.Fatalf("signal path must deactivate, got %d", len(fp.DeactivateCalls))
	}
}

// Stop is idempotent (only one deactivate).
func TestWorker_StopIdempotent(t *testing.T) {
	fp := NewFakePlatform()
	w := NewWorker(testConfig(), fp, NewFakeConsumer(),
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())
	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	w.Stop(time.Second)
	w.Stop(time.Second)
	if len(fp.DeactivateCalls) != 1 {
		t.Fatalf("Stop must be idempotent, deactivate count=%d", len(fp.DeactivateCalls))
	}
}

// badSchemaRecord is a valid-tenant record with an unknown-major schemaVersion
// (v3) — the pipeline classifies it REJECTED_SCHEMA (§A).
func badSchemaRecord(taskID string) Record {
	return Record{
		Topic: "batch.task.dispatch.http.node.w1",
		Value: []byte(`{"taskId":"` + taskID + `","tenantId":"t1","schemaVersion":"v3","idempotencyKey":"idem-` + taskID + `"}`),
	}
}

// P0: an unknown-schema (v3) record must be WITHHELD, never committed. The old
// code left the RejectedSchema branch a no-op, so a following Accepted record's
// Commit() would silently skip past the withheld offset.
func TestWorker_RejectedSchemaWithholdsOffset(t *testing.T) {
	fp := NewFakePlatform()
	consumer := NewFakeConsumer([]Record{badSchemaRecord("v3-1")})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult {
			t.Error("handler must not run for an unknown-schema record")
			return Success(nil, "")
		}), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool { return consumer.withholds() >= 1 })
	w.Stop(time.Second)

	if len(fp.ClaimCalls) != 0 {
		t.Fatalf("unknown-schema record must not be claimed, got %d claims", len(fp.ClaimCalls))
	}
	if consumer.commits() != 0 {
		t.Fatalf("unknown-schema record must NOT commit its offset, got %d commits", consumer.commits())
	}
	if consumer.withholds() != 1 {
		t.Fatalf("unknown-schema record must withhold exactly once, got %d", consumer.withholds())
	}
}

// P0: a foreign-tenant record must be WITHHELD (offset never committed), not a
// silent no-op that a later commit can cross.
func TestWorker_ForeignTenantWithholdsOffset(t *testing.T) {
	fp := NewFakePlatform()
	foreign := Record{Topic: "x", Value: []byte(`{"taskId":"f1","tenantId":"OTHER","schemaVersion":"v1"}`)}
	consumer := NewFakeConsumer([]Record{foreign})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool { return consumer.withholds() >= 1 })
	w.Stop(time.Second)

	if consumer.commits() != 0 {
		t.Fatalf("foreign-tenant record must NOT commit, got %d commits", consumer.commits())
	}
	if consumer.withholds() != 1 {
		t.Fatalf("foreign-tenant record must withhold once, got %d", consumer.withholds())
	}
}

// P1: a hard claim failure (5xx exhausted) must WITHHOLD the offset, not commit
// it — else the task is "offset committed but never claimed" and lost forever.
func TestWorker_ClaimHardFailureWithholdsOffset(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimErr = &RetryExhaustedError{Op: "claim", Last: errors.New("http 500")}
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult {
			t.Error("handler must not run when claim fails")
			return Success(nil, "")
		}), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool { return consumer.withholds() >= 1 })
	w.Stop(time.Second)

	if consumer.commits() != 0 {
		t.Fatalf("hard claim failure must NOT commit the offset, got %d commits", consumer.commits())
	}
	if consumer.withholds() != 1 {
		t.Fatalf("hard claim failure must withhold once, got %d", consumer.withholds())
	}
}

// A 409 (already claimed by another worker) is NOT a failure: commit-skip so we
// don't re-read a task we don't own.
func TestWorker_ClaimIdempotentCommits(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimResp = ClaimResult{Idempotent: true}
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult {
			t.Error("handler must not run on a 409 idempotent claim")
			return Success(nil, "")
		}), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool { return consumer.commits() >= 1 })
	w.Stop(time.Second)

	if consumer.withholds() != 0 {
		t.Fatalf("409 claim must commit-skip, not withhold, got %d withholds", consumer.withholds())
	}
}

// A 404 (task gone) is drop-and-forget: commit-skip (nothing to redeliver).
func TestWorker_ClaimNotFoundCommits(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimErr = &NotFoundError{Op: "claim"}
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool { return consumer.commits() >= 1 })
	w.Stop(time.Second)

	if consumer.withholds() != 0 {
		t.Fatalf("404 claim must commit-skip (drop-and-forget), not withhold, got %d withholds", consumer.withholds())
	}
}

// P1: a FATAL (401/403) heartbeat error must stop the WHOLE worker, not just the
// heartbeat scheduler. A dead heartbeat means the platform will declare us dead
// and redispatch in-flight tasks; continuing to consume would double-run them.
func TestWorker_FatalHeartbeatStopsWorker(t *testing.T) {
	fp := NewFakePlatform()
	// First heartbeat returns a fatal auth error.
	fp.ScriptHeartbeat(protocol.HeartbeatResponse{}, &FatalError{Status: 401, Op: "heartbeat"})

	cfg := testConfig()
	cfg.HeartbeatInterval = time.Millisecond // fire immediately
	consumer := NewFakeConsumer()
	w := NewWorker(cfg, fp, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { return Success(nil, "") }), nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	// 异步停机时使用夹具的加锁快照读取次数，避免直接读取正在更新的切片。
	waitFor(t, 2*time.Second, func() bool {
		_, _, _, _, _, deactivations := fp.Counts()
		return deactivations == 1
	})
	if !consumer.closed() {
		t.Fatalf("fatal heartbeat must stop (close) the consumer")
	}
}

func waitFor(t *testing.T, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("condition not met within %v", timeout)
}

// fix(Stop): a handler that completes DURING graceful drain must report its real
// result (SUCCESS), not be rewritten to CANCELLED. Regression: Stop used to
// rootCancel() before draining, which flipped every in-flight task's cancel
// signal so even gracefully-finishing work landed as CANCELLED + killed the
// lease renewal mid-drain (double-run).
func TestWorker_DrainDoesNotCancelCompletingTask(t *testing.T) {
	fp := NewFakePlatform()
	started := make(chan struct{})
	gate := make(chan struct{})
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		close(started)
		<-gate // block until the test releases us, mid-drain
		if ctx.IsCancelled() {
			return Fail(protocol.ErrorCodeCancelled, "observed cancel")
		}
		return Success(nil, "done")
	})
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer, handler, nil, quietLogger())
	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	<-started

	stopDone := make(chan struct{})
	go func() { w.Stop(2 * time.Second); close(stopDone) }()
	time.Sleep(50 * time.Millisecond) // let Stop enter drain
	close(gate)                       // handler finishes within the drain budget
	<-stopDone

	report, ok := fp.ReportFor("task-1")
	if !ok {
		t.Fatalf("expected a report for task-1")
	}
	if report.Result.ErrorCode != protocol.ErrorCodeSuccess {
		t.Fatalf("task completing during drain must report SUCCESS, got %s", report.Result.ErrorCode)
	}
}

// 容量暂停时已经拉取的记录必须先处理, 后续提交不能跨过它。
func TestWorker_BackpressureRetainsFetchedRecord(t *testing.T) {
	cfg := testConfig()
	cfg.MaxConcurrentTasks = 1
	platform := NewFakePlatform()
	consumer := NewFakeConsumer([]Record{dispatchRecord("first")}, []Record{dispatchRecord("deferred")}, []Record{dispatchRecord("later")})
	release := make(chan struct{})
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		if ctx.TaskID == "first" {
			<-release
		}
		return Success(nil, "done")
	})
	worker := NewWorker(cfg, platform, consumer, handler, nil, quietLogger())
	if err := worker.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer worker.Stop(time.Second)
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	waitFor(t, time.Second, consumer.IsPaused)
	close(release)
	waitFor(t, 2*time.Second, func() bool {
		_, deferred := platform.ReportFor("deferred")
		_, later := platform.ReportFor("later")
		return deferred && later
	})
	if got := consumer.commits(); got != 3 {
		t.Fatalf("commits=%d, want all three claimed records", got)
	}
}

type stopDuringExecutionStartPlatform struct {
	*FakePlatform
	started chan struct{}
	release chan struct{}
}

func (p *stopDuringExecutionStartPlatform) Renew(_ context.Context, _ string, request RenewRequest) (RenewResult, error) {
	if request.Details["executionStarted"] == true {
		close(p.started)
		<-p.release
	}
	return RenewResult{}, nil
}

func TestStopDuringExecutionStartDoesNotStartBusiness(t *testing.T) {
	platform := &stopDuringExecutionStartPlatform{
		FakePlatform: NewFakePlatform(), started: make(chan struct{}), release: make(chan struct{}),
	}
	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	executed := make(chan struct{}, 1)
	worker := NewWorker(testConfig(), platform, consumer,
		HandlerFunc(func(*TaskContext) TaskResult { executed <- struct{}{}; return Success(nil, "ok") }), nil, quietLogger())
	if err := worker.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(platform.release) })
	select {
	case <-platform.started:
	case <-time.After(time.Second):
		t.Fatal("执行开始确认未发出")
	}
	stopped := make(chan struct{})
	go func() { worker.Stop(time.Second); close(stopped) }()
	waitFor(t, time.Second, func() bool { return consumer.wakeups() > 0 })
	releaseOnce.Do(func() { close(platform.release) })
	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("停机未完成")
	}
	select {
	case <-executed:
		t.Error("停机开始后迟到的确认不能启动业务")
	default:
	}
}
