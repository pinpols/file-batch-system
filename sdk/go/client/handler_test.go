package client

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Cancellation signal: handler observes cancel after MarkCancelled.
func TestCancellationSignal(t *testing.T) {
	sig := NewCancellationSignal(context.Background())
	if sig.IsCancellationRequested() {
		t.Fatalf("fresh signal should not be cancelled")
	}
	sig.MarkCancelled()
	if !sig.IsCancellationRequested() {
		t.Fatalf("expected cancellation after MarkCancelled")
	}
	// idempotent
	sig.MarkCancelled()
}

// Parent context cancellation propagates to the signal.
func TestCancellationSignal_ParentPropagates(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	sig := NewCancellationSignal(ctx)
	cancel()
	if !sig.IsCancellationRequested() {
		t.Fatalf("parent cancel should propagate to signal")
	}
}

// A cooperative handler stops on cancellation.
func TestHandler_CooperativeCancel(t *testing.T) {
	sig := NewCancellationSignal(context.Background())
	h := HandlerFunc(func(ctx *TaskContext) TaskResult {
		for i := 0; i < 1000; i++ {
			if ctx.Cancellation.IsCancellationRequested() {
				return Fail(protocol.ErrorCodeCancelled, "stopped early")
			}
			if i == 5 {
				ctx.Cancellation.MarkCancelled()
			}
		}
		return Success(nil, "ran to completion")
	})
	res := h.Execute(&TaskContext{Cancellation: sig, Progress: NoopProgressReporter{}})
	if res.ErrorCode != protocol.ErrorCodeCancelled {
		t.Fatalf("expected CANCELLED, got %s", res.ErrorCode)
	}
}

// TaskResult json tags are the exact §B contract names.
func TestTaskResult_JSONFieldNames(t *testing.T) {
	res := TaskResult{
		ErrorCode:     protocol.ErrorCodeExecutionFailed,
		Outputs:       map[string]any{"rows": 10},
		ResultSummary: "boom",
	}
	b, err := json.Marshal(res)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	for _, k := range []string{"errorCode", "outputs", "resultSummary"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("missing required field %q in %s", k, b)
		}
	}
	// the forbidden names must NOT appear.
	for _, k := range []string{"errorClass", "output", "errorMessage"} {
		if _, ok := m[k]; ok {
			t.Fatalf("forbidden field %q present in %s", k, b)
		}
	}
}

// ProgressReporter records events.
func TestProgressReporter(t *testing.T) {
	r := &RecordingProgressReporter{}
	r.Report(50, "half")
	r.Report(100, "done")
	if len(r.Events) != 2 || r.Events[1].Percent != 100 {
		t.Fatalf("expected 2 progress events, got %+v", r.Events)
	}
}
