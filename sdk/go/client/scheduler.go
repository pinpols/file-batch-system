package client

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// DefaultHeartbeatInterval / DefaultLeaseRenewInterval — byo-sdk-guide §5.
const (
	DefaultHeartbeatInterval  = 30 * time.Second
	DefaultLeaseRenewInterval = 60 * time.Second
)

// HeartbeatScheduler runs the §1.3 heartbeat loop: every interval, POST
// heartbeat, apply the reverse-directive via protocol.ApplyHeartbeatDirective
// (FSM transition + Kafka pause), and DYNAMICALLY reset its own ticker interval
// from nextHeartbeatHint (the Java SDK gap this SDK closes). DRAINING is
// surfaced to the worker via the drain callback.
type HeartbeatScheduler struct {
	transport  Transport
	fsm        *FSM
	workerCode string
	tenantID   string
	logger     *log.Logger

	interval time.Duration
	// inFlight supplies the count reported on each heartbeat.
	inFlight func() int
	// onDrain is invoked once when the platform signals DRAINING (drainThenDeactivate).
	onDrain func()

	mu          sync.Mutex
	lastApplied protocol.Decision // exposed for tests
	drained     bool
}

// HeartbeatOption configures a HeartbeatScheduler.
type HeartbeatOption func(*HeartbeatScheduler)

// WithHeartbeatInterval overrides the initial interval (tests pass a tiny value
// to avoid real 30s waits).
func WithHeartbeatInterval(d time.Duration) HeartbeatOption {
	return func(s *HeartbeatScheduler) { s.interval = d }
}

// WithHeartbeatInFlight injects the in-flight count provider.
func WithHeartbeatInFlight(f func() int) HeartbeatOption {
	return func(s *HeartbeatScheduler) { s.inFlight = f }
}

// WithOnDrain registers the DRAINING callback (lifecycle wires it to Stop).
func WithOnDrain(f func()) HeartbeatOption {
	return func(s *HeartbeatScheduler) { s.onDrain = f }
}

// WithHeartbeatLogger injects a logger.
func WithHeartbeatLogger(l *log.Logger) HeartbeatOption {
	return func(s *HeartbeatScheduler) { s.logger = l }
}

// NewHeartbeatScheduler builds a scheduler. Transport + FSM are injectable so
// tests drive it with a FakeTransport and assert without real network/time.
func NewHeartbeatScheduler(transport Transport, fsm *FSM, workerCode, tenantID string, opts ...HeartbeatOption) *HeartbeatScheduler {
	s := &HeartbeatScheduler{
		transport:  transport,
		fsm:        fsm,
		workerCode: workerCode,
		tenantID:   tenantID,
		logger:     log.Default(),
		interval:   DefaultHeartbeatInterval,
		inFlight:   func() int { return 0 },
	}
	for _, o := range opts {
		o(s)
	}
	return s
}

// Interval returns the current (possibly hint-adjusted) heartbeat interval.
func (s *HeartbeatScheduler) Interval() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.interval
}

// LastDecision returns the decision applied on the most recent beat (tests).
func (s *HeartbeatScheduler) LastDecision() protocol.Decision {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastApplied
}

// Beat performs ONE heartbeat round-trip + directive application. Extracted so
// tests exercise the directive logic without spinning the ticker. Returns the
// applied decision.
func (s *HeartbeatScheduler) Beat(ctx context.Context) (protocol.Decision, error) {
	resp, err := s.transport.Heartbeat(ctx, s.workerCode, HeartbeatRequest{
		WorkerCode: s.workerCode,
		TenantID:   s.tenantID,
		InFlight:   s.inFlight(),
		State:      string(s.fsm.State()),
	})
	if err != nil {
		return protocol.Decision{}, err
	}
	d := protocol.ApplyHeartbeatDirective(resp)

	// FSM transition.
	if d.FsmTransition != nil {
		s.fsm.SetState(WorkerState(*d.FsmTransition))
	}
	// Kafka pause/resume.
	s.fsm.ApplyKafkaDirective(d.Kafka)

	// Dynamic re-pacing from nextHeartbeatHint (§1.3, the closed Java gap).
	s.mu.Lock()
	if d.HeartbeatNextIntervalMs != nil {
		s.interval = time.Duration(*d.HeartbeatNextIntervalMs) * time.Millisecond
	}
	s.lastApplied = d
	fireDrain := false
	if d.DrainThenDeactivate != nil && *d.DrainThenDeactivate && !s.drained {
		s.drained = true
		fireDrain = true
	}
	s.mu.Unlock()

	if fireDrain && s.onDrain != nil {
		s.logger.Printf("INFO platform signalled DRAINING worker=%s -> draining", s.workerCode)
		s.onDrain()
	}
	return d, nil
}

// Run drives the heartbeat loop until ctx is cancelled. The ticker interval is
// re-read each tick so nextHeartbeatHint changes take effect on the next beat.
func (s *HeartbeatScheduler) Run(ctx context.Context) {
	timer := time.NewTimer(s.Interval())
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			if _, err := s.Beat(ctx); err != nil {
				if ctx.Err() != nil {
					return
				}
				if IsFatal(err) {
					s.logger.Printf("ERROR fatal heartbeat error worker=%s: %v (stopping scheduler)", s.workerCode, err)
					return
				}
				s.logger.Printf("WARN heartbeat error worker=%s: %v", s.workerCode, err)
			}
			timer.Reset(s.Interval())
		}
	}
}

// ---------------------------------------------------------------------------
// LeaseRenewalScheduler — §1.4
// ---------------------------------------------------------------------------

// InFlightRegistry tracks in-flight tasks so the lease scheduler can iterate
// them and the worker can signal cancellation.
type InFlightRegistry struct {
	mu    sync.RWMutex
	tasks map[string]*CancellationSignal
}

// NewInFlightRegistry builds an empty registry.
func NewInFlightRegistry() *InFlightRegistry {
	return &InFlightRegistry{tasks: map[string]*CancellationSignal{}}
}

// Add registers a task's cancellation signal.
func (r *InFlightRegistry) Add(taskID string, sig *CancellationSignal) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.tasks[taskID] = sig
}

// Remove drops a task (completed or lease lost).
func (r *InFlightRegistry) Remove(taskID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.tasks, taskID)
}

// Count returns the number of in-flight tasks.
func (r *InFlightRegistry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.tasks)
}

// snapshot copies the current task->signal map for safe iteration.
func (r *InFlightRegistry) snapshot() map[string]*CancellationSignal {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make(map[string]*CancellationSignal, len(r.tasks))
	for k, v := range r.tasks {
		out[k] = v
	}
	return out
}

// LeaseRenewalScheduler runs the §1.4 loop: every interval, renew each in-flight
// lease; on cancelRequested it MarkCancelled() the task's signal; on 404/409 it
// drops the task locally (lease already reclaimed).
type LeaseRenewalScheduler struct {
	transport  Transport
	registry   *InFlightRegistry
	workerCode string
	tenantID   string
	logger     *log.Logger
	interval   time.Duration
}

// LeaseOption configures a LeaseRenewalScheduler.
type LeaseOption func(*LeaseRenewalScheduler)

// WithLeaseInterval overrides the renew interval (tests use a tiny value).
func WithLeaseInterval(d time.Duration) LeaseOption {
	return func(s *LeaseRenewalScheduler) { s.interval = d }
}

// WithLeaseLogger injects a logger.
func WithLeaseLogger(l *log.Logger) LeaseOption {
	return func(s *LeaseRenewalScheduler) { s.logger = l }
}

// NewLeaseRenewalScheduler builds a lease scheduler.
func NewLeaseRenewalScheduler(transport Transport, registry *InFlightRegistry, workerCode, tenantID string, opts ...LeaseOption) *LeaseRenewalScheduler {
	s := &LeaseRenewalScheduler{
		transport:  transport,
		registry:   registry,
		workerCode: workerCode,
		tenantID:   tenantID,
		logger:     log.Default(),
		interval:   DefaultLeaseRenewInterval,
	}
	for _, o := range opts {
		o(s)
	}
	return s
}

// RenewOnce renews all currently in-flight leases exactly once (extracted for
// tests). Returns the set of task ids dropped this round.
func (s *LeaseRenewalScheduler) RenewOnce(ctx context.Context) []string {
	var dropped []string
	for taskID, sig := range s.registry.snapshot() {
		resp, err := s.transport.Renew(ctx, taskID, RenewRequest{
			WorkerID: s.workerCode,
			TenantID: s.tenantID,
		})
		if err != nil {
			// 404 (lease gone) -> drop locally; handler's report would be
			// rejected anyway (§1.4).
			var nf *NotFoundError
			if errors.As(err, &nf) {
				s.logger.Printf("WARN lease gone taskId=%s, dropping: %v", taskID, err)
				sig.MarkCancelled()
				s.registry.Remove(taskID)
				dropped = append(dropped, taskID)
				continue
			}
			s.logger.Printf("WARN renew error taskId=%s: %v", taskID, err)
			continue
		}
		// 409 (lease reclaimed / zombie claim): stop the handler and drop the
		// task locally, same as 404 (openapi renew 409 semantics).
		if resp.Revoked {
			s.logger.Printf("WARN lease revoked (409) taskId=%s, cancelling handler and dropping", taskID)
			sig.MarkCancelled()
			s.registry.Remove(taskID)
			dropped = append(dropped, taskID)
			continue
		}
		// ApplyRenew turns cancelRequested into a cancel decision.
		if d := protocol.ApplyRenew(resp.RenewResponse); d.CancelRequested != nil && *d.CancelRequested {
			s.logger.Printf("INFO cancelRequested taskId=%s -> signalling handler", taskID)
			sig.MarkCancelled()
		}
	}
	return dropped
}

// Run drives the lease-renewal loop until ctx is cancelled.
func (s *LeaseRenewalScheduler) Run(ctx context.Context) {
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.RenewOnce(ctx)
		}
	}
}
