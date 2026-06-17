package client

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

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

// Start -> consume one task -> handler runs -> report SUCCESS. Then Stop ordering:
// wakeup, drain, deactivate.
func TestWorker_StartConsumeReportStop(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimResp = ClaimResult{EffectiveConfig: map[string]any{"batchSize": 10}, TraceID: "tr-1"}

	var ran bool
	var mu sync.Mutex
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		mu.Lock()
		ran = true
		mu.Unlock()
		if ctx.TraceID != "tr-1" {
			t.Errorf("expected traceId tr-1, got %q", ctx.TraceID)
		}
		return Success(map[string]any{"ok": true}, "done")
	})

	consumer := NewFakeConsumer([]Record{dispatchRecord("task-1")})
	w := NewWorker(testConfig(), fp, consumer, handler, nil, quietLogger())

	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
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
	if report.IdempotencyKey != "idem-task-1" {
		t.Fatalf("expected idempotency key propagated, got %q", report.IdempotencyKey)
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
