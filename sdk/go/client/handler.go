package client

import (
	"context"
	"sync"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Handler SPI — the tenant-implemented surface. Mirrors the Java SDK
// TaskContext / CancellationSignal / ProgressReporter / TaskResult / TaskHandler
// (byo-sdk-guide §1.6, wire-protocol §4). Pure value + context types; no IO.

// CancellationSignal is the cooperative-cancellation primitive handed to a
// handler. Backed by a context.Context whose cancel func flips on cancel.
// IsCancellationRequested is the cheap hot-loop poll; MarkCancelled is invoked
// by the lease-renewal scheduler when renew returns cancelRequested=true.
type CancellationSignal struct {
	ctx    context.Context
	cancel context.CancelFunc
	once   sync.Once
}

// NewCancellationSignal builds a signal whose parent governs the overall task
// lifetime (e.g. worker shutdown also cancels in-flight tasks).
func NewCancellationSignal(parent context.Context) *CancellationSignal {
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithCancel(parent)
	return &CancellationSignal{ctx: ctx, cancel: cancel}
}

// IsCancellationRequested reports whether cancellation has been signalled
// (either via MarkCancelled or the parent context being cancelled).
func (c *CancellationSignal) IsCancellationRequested() bool {
	select {
	case <-c.ctx.Done():
		return true
	default:
		return false
	}
}

// MarkCancelled flips the signal. Idempotent.
func (c *CancellationSignal) MarkCancelled() {
	c.once.Do(c.cancel)
}

// Context exposes the underlying context for select-based waits.
func (c *CancellationSignal) Context() context.Context { return c.ctx }

// ProgressReporter receives coarse-grained progress from a running handler.
// The default no-op records nothing; tests/observability can substitute one.
type ProgressReporter interface {
	// Report posts a 0..100 percentage with an optional human message.
	Report(percent int, message string)
}

// NoopProgressReporter discards progress.
type NoopProgressReporter struct{}

func (NoopProgressReporter) Report(int, string) {}

// RecordingProgressReporter captures progress events for tests.
type RecordingProgressReporter struct {
	mu     sync.Mutex
	Events []ProgressEvent
}

// ProgressEvent is one captured progress callback.
type ProgressEvent struct {
	Percent int
	Message string
}

// Report appends an event.
func (r *RecordingProgressReporter) Report(percent int, message string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.Events = append(r.Events, ProgressEvent{Percent: percent, Message: message})
}

// TaskContext is the per-task execution context passed to a handler. Carries the
// claimed EffectiveConfig snapshot, trace id for OTel stitching, the cancellation
// signal, and the progress sink.
type TaskContext struct {
	TaskID          string
	EffectiveConfig map[string]any
	TraceID         string
	Cancellation    *CancellationSignal
	Progress        ProgressReporter
}

// TaskResult is the handler's terminal outcome. Field names + json tags are a
// HARD contract (wire-protocol §B field-name 红线): the platform's
// TaskExecutionReportDto reads exactly errorCode / outputs / resultSummary;
// any other name is silently dropped.
type TaskResult struct {
	ErrorCode     protocol.ErrorCode `json:"errorCode"`
	Outputs       map[string]any     `json:"outputs"`
	ResultSummary string             `json:"resultSummary"`
}

// Success builds a SUCCESS result.
func Success(outputs map[string]any, summary string) TaskResult {
	return TaskResult{ErrorCode: protocol.ErrorCodeSuccess, Outputs: outputs, ResultSummary: summary}
}

// Fail builds a failed result with the given code + summary.
func Fail(code protocol.ErrorCode, summary string) TaskResult {
	return TaskResult{ErrorCode: code, ResultSummary: summary}
}

// IsSuccess reports whether the result is a success outcome.
func (r TaskResult) IsSuccess() bool {
	return r.ErrorCode == "" || r.ErrorCode == protocol.ErrorCodeSuccess
}

// TaskHandler is the tenant business-logic SPI. Execute runs the task and
// returns its terminal result. Implementations MUST poll
// ctx.Cancellation.IsCancellationRequested() cooperatively.
type TaskHandler interface {
	Execute(ctx *TaskContext) TaskResult
}

// HandlerFunc adapts a plain func to a TaskHandler.
type HandlerFunc func(ctx *TaskContext) TaskResult

// Execute implements TaskHandler.
func (f HandlerFunc) Execute(ctx *TaskContext) TaskResult { return f(ctx) }
