package client

import (
	"errors"
	"fmt"
	"sync"
)

// Checkpoint / reliable-commit / cooperative-cancel PRIMITIVES (ADR-037
// 决策一/二/三). The Go SDK is a THIN BYO SDK: it exposes these primitives on
// the task context for a tenant's handler to call, but ships NO typed
// import/export/process templates. The handler drives its own loop and calls
// ctx.Commit(breakPosition) at each business-batch boundary.
//
// HARD CONTRACT (决策二, 强约束): a real SdkCheckpoint impl MUST persist the
// checkpoint state in the SAME TRANSACTION as the business data it describes.
// If the business data commits but the checkpoint does not (or vice-versa),
// a crash leaves the breakpoint and the data torn apart and resume becomes
// unreliable (duplicate processing or lost rows). The SDK-side Commit only
// saves the checkpoint + reports progress; committing the business data is the
// tenant's responsibility and MUST be fused into the same transaction boundary
// as the checkpoint save.

// SdkCheckpointState is the resumable breakpoint of a long-running task.
// Mirrors the Java record SdkCheckpointState. BreakPosition is the data's own
// key/range (a record primary key or range key) — NOT a Kafka offset — so that
// resume reads back exactly where business processing stopped.
type SdkCheckpointState struct {
	// BreakPosition is the already-processed high-water mark (record PK / range).
	BreakPosition map[string]any
	// SucceedCount / FailCount are cumulative counters across resumes; restoring
	// them keeps progress from resetting to zero on resume.
	SucceedCount int64
	FailCount    int64
	// Completed marks the task as idempotently finished; a resumed run that
	// loads a completed state should short-circuit to success.
	Completed bool
}

// SdkCheckpoint is the breakpoint persistence SPI (决策一). The SEMANTICS are
// defined by the SDK; PERSISTENCE is the tenant's. The SDK does not mandate
// where state lives (a tenant control table, KV, object storage); it only
// requires read/write.
type SdkCheckpoint interface {
	// Load reads back the last checkpoint for taskID. On first run it returns
	// (nil, nil) — a nil state, no error — meaning "no checkpoint yet".
	Load(taskID string) (*SdkCheckpointState, error)
	// Save persists the checkpoint for taskID.
	//
	// HARD CONTRACT: a real impl MUST run this in the SAME transaction as the
	// business-data write it describes (see file header). The in-memory default
	// below is for tests/local use only and provides NO such atomicity.
	Save(taskID string, state SdkCheckpointState) error
}

// InMemoryCheckpoint is the default SdkCheckpoint impl: a mutex-guarded map.
//
// It is intended for tests and local development ONLY. It does NOT participate
// in any business transaction, so it CANNOT satisfy the 决策二 same-transaction
// guarantee — a production tenant MUST supply an impl that fuses Save into the
// business-data transaction.
type InMemoryCheckpoint struct {
	mu     sync.Mutex
	states map[string]SdkCheckpointState
}

// NewInMemoryCheckpoint builds an empty in-memory checkpoint store.
func NewInMemoryCheckpoint() *InMemoryCheckpoint {
	return &InMemoryCheckpoint{states: make(map[string]SdkCheckpointState)}
}

// Load returns a copy of the stored state, or (nil, nil) on first run.
func (c *InMemoryCheckpoint) Load(taskID string) (*SdkCheckpointState, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	s, ok := c.states[taskID]
	if !ok {
		return nil, nil
	}
	cp := s.clone()
	return &cp, nil
}

// Save stores a defensive copy of the state.
func (c *InMemoryCheckpoint) Save(taskID string, state SdkCheckpointState) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.states[taskID] = state.clone()
	return nil
}

// clone deep-copies the BreakPosition map so callers cannot mutate stored state.
func (s SdkCheckpointState) clone() SdkCheckpointState {
	out := s
	if s.BreakPosition != nil {
		bp := make(map[string]any, len(s.BreakPosition))
		for k, v := range s.BreakPosition {
			bp[k] = v
		}
		out.BreakPosition = bp
	}
	return out
}

// SdkTaskStopped is the cooperative-cancel sentinel (决策三). After a SUCCESSFUL
// Commit at a safe batch boundary, if the task has been cancelled, Commit
// returns an SdkTaskStopped carrying the last committed BreakPosition. The
// handler MUST let it propagate (MUST NOT swallow it); the worker loop maps it
// to a CANCELLED terminal report — NOT a failure. Cancellation therefore always
// stops between two batches, never mid-batch, leaving no half-written data.
type SdkTaskStopped struct {
	// BreakPosition is the last safely committed breakpoint at the stop point.
	BreakPosition map[string]any
}

// Error implements error.
func (e *SdkTaskStopped) Error() string {
	return fmt.Sprintf("sdk task stopped at break position %v", e.BreakPosition)
}

// NewSdkTaskStopped builds the sentinel for a given committed break position.
func NewSdkTaskStopped(breakPosition map[string]any) *SdkTaskStopped {
	return &SdkTaskStopped{BreakPosition: breakPosition}
}

// IsSdkTaskStopped reports whether err is (or wraps) an SdkTaskStopped, and
// returns the sentinel for break-position extraction. Used by the worker loop's
// handler-result mapping.
func IsSdkTaskStopped(err error) (*SdkTaskStopped, bool) {
	var s *SdkTaskStopped
	if errors.As(err, &s) {
		return s, true
	}
	return nil, false
}
