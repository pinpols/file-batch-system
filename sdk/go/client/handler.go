package client

import (
	"context"
	"fmt"
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
//
// ADR-037 primitives: checkpoint (决策一) is exposed via Checkpoint(); the
// three-in-one reliable commit (决策二) via Commit(breakPosition). The handler
// drives its own batch loop and calls Commit at each business-batch boundary;
// the SDK supplies NO typed templates (thin BYO SDK).
type TaskContext struct {
	TaskID          string
	EffectiveConfig map[string]any
	TraceID         string
	Cancellation    *CancellationSignal
	Progress        ProgressReporter

	// checkpoint is the breakpoint persistence SPI (决策一). Defaults to an
	// InMemoryCheckpoint when the worker leaves it nil; a production handler
	// should set a same-transaction impl (see checkpoint.go header).
	checkpoint SdkCheckpoint
	// ReportInterval rate-limits progress reporting inside Commit: progress is
	// reported once every ReportInterval commits (决策二). Zero/negative means
	// "report on every commit" (interval of 1).
	ReportInterval int
	// SelfReport, when true, disables Commit's automatic progress report so the
	// handler can drive ctx.Progress.Report itself (决策二 selfReport switch).
	SelfReport bool

	// SucceedCount / FailCount are the live cumulative counters carried into
	// each progress report; the handler updates these as it processes batches
	// (typically after restoring them from a loaded checkpoint on resume).
	SucceedCount int64
	FailCount    int64

	// commitCounter counts Commit calls for modulo rate-limiting.
	commitCounter int64
}

// IsCancelled reports whether cooperative cancellation has been requested. It is
// the hot-loop poll the handler checks between batches; Commit also checks it.
func (c *TaskContext) IsCancelled() bool {
	return c.Cancellation != nil && c.Cancellation.IsCancellationRequested()
}

// Checkpoint returns the breakpoint persistence SPI (决策一). It is never nil:
// if none was wired, a process-local InMemoryCheckpoint is lazily installed.
func (c *TaskContext) Checkpoint() SdkCheckpoint {
	if c.checkpoint == nil {
		c.checkpoint = NewInMemoryCheckpoint()
	}
	return c.checkpoint
}

// SetCheckpoint installs the checkpoint SPI (used by the worker / tests).
func (c *TaskContext) SetCheckpoint(cp SdkCheckpoint) { c.checkpoint = cp }

// Commit is the three-in-one reliable commit at a business-batch boundary
// (决策二). It:
//
//  1. saves the checkpoint (breakPosition + current counters, not completed);
//  2. reports progress, rate-limited to once every ReportInterval commits
//     (unless SelfReport is set, in which case the handler reports itself);
//  3. after a SUCCESSFUL save+report, if the task has been cancelled, returns
//     an *SdkTaskStopped carrying breakPosition (决策三) — a safe stop between
//     batches. The handler MUST propagate this error, not swallow it.
//
// STRONG CONSTRAINT: the BUSINESS-DATA commit is the tenant's, and a real
// SdkCheckpoint impl MUST fuse step 1 into the same transaction as that
// business-data write (see checkpoint.go header). The SDK cannot enforce this;
// code review must.
func (c *TaskContext) Commit(breakPosition map[string]any) error {
	if err := c.Checkpoint().Save(c.TaskID, SdkCheckpointState{
		BreakPosition: breakPosition,
		SucceedCount:  c.SucceedCount,
		FailCount:     c.FailCount,
		Completed:     false,
	}); err != nil {
		return err
	}

	c.commitCounter++
	interval := c.ReportInterval
	if interval < 1 {
		interval = 1
	}
	if !c.SelfReport && c.commitCounter%int64(interval) == 0 && c.Progress != nil {
		c.Progress.Report(0, progressMessage(c.SucceedCount, c.FailCount, breakPosition))
	}

	if c.IsCancelled() {
		return NewSdkTaskStopped(breakPosition)
	}
	return nil
}

// progressMessage renders a coarse progress line for the rate-limited report.
func progressMessage(succeed, fail int64, breakPosition map[string]any) string {
	return fmt.Sprintf("succeed=%d fail=%d breakPosition=%v", succeed, fail, breakPosition)
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
