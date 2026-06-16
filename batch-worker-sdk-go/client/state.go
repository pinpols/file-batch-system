package client

import "sync"

// WorkerState is the 4-state runtime FSM (byo-sdk-guide §1.5), mirroring
// protocol.WorkerRuntimeStates.
type WorkerState string

const (
	StateNormal   WorkerState = "NORMAL"
	StateDegraded WorkerState = "DEGRADED"
	StatePaused   WorkerState = "PAUSED"
	StateDraining WorkerState = "DRAINING"
)

// FSM holds the worker's current runtime state plus a Kafka pause flag, guarded
// by a mutex (heartbeat scheduler, consumer, and lifecycle all touch it).
//
// The Kafka "pause" is modeled as a boolean the consumer consults before
// accepting a record — equivalent to assignment.pause()/resume() on a real
// client (which sits behind the Consumer interface).
type FSM struct {
	mu     sync.RWMutex
	state  WorkerState
	paused bool
}

// NewFSM starts in NORMAL, not paused.
func NewFSM() *FSM {
	return &FSM{state: StateNormal}
}

// State returns the current FSM state.
func (f *FSM) State() WorkerState {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.state
}

// SetState transitions to s.
func (f *FSM) SetState(s WorkerState) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.state = s
}

// Paused reports whether Kafka consumption is paused.
func (f *FSM) Paused() bool {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.paused
}

// SetPaused sets the Kafka pause flag.
func (f *FSM) SetPaused(p bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.paused = p
}

// ApplyKafkaDirective maps a decision's Kafka field ("pause"/"none"/...) onto
// the pause flag. "pause" pauses; "none" resumes; other values (subscribe,
// wakeup, "") are no-ops here (handled elsewhere or not pause-related).
func (f *FSM) ApplyKafkaDirective(kafka *string) {
	if kafka == nil {
		return
	}
	switch *kafka {
	case "pause":
		f.SetPaused(true)
	case "none":
		f.SetPaused(false)
	}
}
