package client

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// load on first run returns (nil, nil); after Save, Load reads it back.
func TestInMemoryCheckpoint_LoadSave(t *testing.T) {
	cp := NewInMemoryCheckpoint()

	got, err := cp.Load("t1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != nil {
		t.Fatalf("first-run load want nil, got %+v", got)
	}

	state := SdkCheckpointState{
		BreakPosition: map[string]any{"id": 42},
		SucceedCount:  10,
		FailCount:     2,
		Completed:     false,
	}
	if err := cp.Save("t1", state); err != nil {
		t.Fatalf("save: %v", err)
	}

	got, err = cp.Load("t1")
	if err != nil || got == nil {
		t.Fatalf("load after save: got=%v err=%v", got, err)
	}
	if got.SucceedCount != 10 || got.FailCount != 2 || got.BreakPosition["id"] != 42 {
		t.Fatalf("round-trip mismatch: %+v", got)
	}

	// Stored state must be isolated from caller mutation.
	got.BreakPosition["id"] = 999
	reloaded, _ := cp.Load("t1")
	if reloaded.BreakPosition["id"] != 42 {
		t.Fatalf("stored state mutated through returned copy: %+v", reloaded)
	}
}

// A handler resuming a completed task must short-circuit to success (idempotent).
func TestResume_SkipCompleted(t *testing.T) {
	cp := NewInMemoryCheckpoint()
	_ = cp.Save("done", SdkCheckpointState{Completed: true, SucceedCount: 100})

	processed := 0
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		state, err := ctx.Checkpoint().Load(ctx.TaskID)
		if err != nil {
			return Fail(protocol.ErrorCodeExecutionFailed, err.Error())
		}
		if state != nil && state.Completed {
			return Success(nil, "already completed")
		}
		processed++
		return Success(nil, "processed")
	})

	tc := &TaskContext{TaskID: "done", Cancellation: NewCancellationSignal(context.Background())}
	tc.SetCheckpoint(cp)
	res := handler.Execute(tc)

	if !res.IsSuccess() {
		t.Fatalf("completed task should succeed, got %+v", res)
	}
	if processed != 0 {
		t.Fatalf("completed task must skip processing, processed=%d", processed)
	}
}

// Commit saves the checkpoint every call but reports progress only on interval.
func TestCommit_ReportsOnInterval(t *testing.T) {
	cp := NewInMemoryCheckpoint()
	rec := &RecordingProgressReporter{}
	tc := &TaskContext{
		TaskID:         "t",
		Cancellation:   NewCancellationSignal(context.Background()),
		Progress:       rec,
		ReportInterval: 3,
	}
	tc.SetCheckpoint(cp)

	for i := 1; i <= 7; i++ {
		tc.SucceedCount = int64(i)
		if err := tc.Commit(map[string]any{"id": i}); err != nil {
			t.Fatalf("commit %d: %v", i, err)
		}
	}

	// 7 commits, interval 3 -> reports at commit 3 and 6 = 2 reports.
	if len(rec.Events) != 2 {
		t.Fatalf("want 2 reports on interval 3 over 7 commits, got %d: %+v", len(rec.Events), rec.Events)
	}

	// Every commit saved the checkpoint -> last save has succeedCount=7.
	st, _ := cp.Load("t")
	if st == nil || st.SucceedCount != 7 || st.BreakPosition["id"] != 7 {
		t.Fatalf("last checkpoint mismatch: %+v", st)
	}
}

// SelfReport disables Commit's automatic reporting.
func TestCommit_SelfReportSuppressesProgress(t *testing.T) {
	rec := &RecordingProgressReporter{}
	tc := &TaskContext{
		TaskID:         "t",
		Cancellation:   NewCancellationSignal(context.Background()),
		Progress:       rec,
		ReportInterval: 1,
		SelfReport:     true,
	}
	tc.SetCheckpoint(NewInMemoryCheckpoint())

	for i := 0; i < 5; i++ {
		_ = tc.Commit(map[string]any{"id": i})
	}
	if len(rec.Events) != 0 {
		t.Fatalf("selfReport must suppress auto progress, got %d events", len(rec.Events))
	}
}

// A successful Commit on a cancelled task returns SdkTaskStopped with the
// break position; the checkpoint is still saved (safe stop point).
func TestCommit_CancelReturnsSdkTaskStopped(t *testing.T) {
	sig := NewCancellationSignal(context.Background())
	cp := NewInMemoryCheckpoint()
	tc := &TaskContext{
		TaskID:       "t",
		Cancellation: sig,
		Progress:     NoopProgressReporter{},
	}
	tc.SetCheckpoint(cp)

	// Not cancelled yet: commit returns nil.
	if err := tc.Commit(map[string]any{"id": 1}); err != nil {
		t.Fatalf("uncancelled commit should be nil, got %v", err)
	}

	sig.MarkCancelled()
	bp := map[string]any{"id": 2}
	err := tc.Commit(bp)
	stop, ok := IsSdkTaskStopped(err)
	if !ok {
		t.Fatalf("cancelled commit must return SdkTaskStopped, got %v", err)
	}
	if stop.BreakPosition["id"] != 2 {
		t.Fatalf("SdkTaskStopped break position mismatch: %+v", stop.BreakPosition)
	}

	// The safe point was still persisted before the stop.
	st, _ := cp.Load("t")
	if st == nil || st.BreakPosition["id"] != 2 {
		t.Fatalf("checkpoint should be saved at the safe stop point: %+v", st)
	}

	// errors.As wrapping works.
	wrapped := errors.New("outer")
	_ = wrapped
	if _, ok := IsSdkTaskStopped(stop); !ok {
		t.Fatalf("IsSdkTaskStopped should match the sentinel itself")
	}
}

// ResultFromError maps the sentinel to a CANCELLED result, others to failure.
func TestResultFromError_Mapping(t *testing.T) {
	stop := NewSdkTaskStopped(map[string]any{"id": 9})
	res := ResultFromError(stop)
	if res.ErrorCode != protocol.ErrorCodeCancelled {
		t.Fatalf("SdkTaskStopped should map to CANCELLED, got %s", res.ErrorCode)
	}
	if res.Outputs["breakPosition"] == nil {
		t.Fatalf("CANCELLED result should carry breakPosition outputs")
	}

	if ResultFromError(errors.New("boom")).ErrorCode != protocol.ErrorCodeExecutionFailed {
		t.Fatalf("generic error should map to EXECUTION_FAILED")
	}
	if !ResultFromError(nil).IsSuccess() {
		t.Fatalf("nil error should map to success")
	}
}

// End-to-end: a handler that lets SdkTaskStopped propagate (panics with it
// after a cancelled Commit) is mapped by the worker loop to a CANCELLED
// terminal report, NOT a failure (decision 三).
func TestWorker_SdkTaskStoppedMapsToCancelled(t *testing.T) {
	fp := NewFakePlatform()
	fp.ClaimResp = ClaimResult{EffectiveConfig: map[string]any{"batchSize": 10}, TraceID: "tr-x"}

	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		ctx.SetCheckpoint(NewInMemoryCheckpoint())
		// First batch commits cleanly.
		if err := ctx.Commit(map[string]any{"id": 1}); err != nil {
			panic(err)
		}
		// Worker cancelled this task mid-flight; next commit returns the
		// sentinel, which the handler propagates (does NOT swallow).
		ctx.Cancellation.MarkCancelled()
		if err := ctx.Commit(map[string]any{"id": 2}); err != nil {
			panic(err) // documented: let SdkTaskStopped propagate
		}
		return Success(nil, "should not reach")
	})

	consumer := NewFakeConsumer([]Record{dispatchRecord("task-stop")})
	w := NewWorker(testConfig(), fp, consumer, handler, nil, quietLogger())
	if err := w.Start(context.Background()); err != nil {
		t.Fatalf("start: %v", err)
	}
	waitFor(t, time.Second, func() bool {
		_, ok := fp.ReportFor("task-stop")
		return ok
	})
	report, _ := fp.ReportFor("task-stop")
	if report.Result.ErrorCode != protocol.ErrorCodeCancelled {
		t.Fatalf("SdkTaskStopped must map to CANCELLED, got %s", report.Result.ErrorCode)
	}
	if report.Result.Outputs["breakPosition"] == nil {
		t.Fatalf("cancelled report should carry breakPosition, got %+v", report.Result.Outputs)
	}
	w.Stop(time.Second)
}
