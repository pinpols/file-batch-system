package client

import (
	"context"
	"log"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Config is the worker's static configuration.
type Config struct {
	WorkerCode     string
	TenantID       string
	BuildID        string
	SDKVersion     string
	CapabilityTags []string
	// RegisterAttributes is the fingerprint attribute map sent at register time.
	// It is scanned by SensitiveValidator (§1.8) before send.
	RegisterAttributes map[string]any
	MaxConcurrentTasks int
	// HeartbeatInterval / LeaseRenewInterval default to the §5 values when zero.
	HeartbeatInterval  time.Duration
	LeaseRenewInterval time.Duration
	// StopTimeout is the graceful-stop budget (default 30s, §1.6).
	StopTimeout time.Duration
}

func (c Config) withDefaults() Config {
	if c.MaxConcurrentTasks <= 0 {
		c.MaxConcurrentTasks = 1
	}
	if c.HeartbeatInterval <= 0 {
		c.HeartbeatInterval = DefaultHeartbeatInterval
	}
	if c.LeaseRenewInterval <= 0 {
		c.LeaseRenewInterval = DefaultLeaseRenewInterval
	}
	if c.StopTimeout <= 0 {
		c.StopTimeout = 30 * time.Second
	}
	return c
}

// Worker wires the runtime engine: FSM + transport + consumer + schedulers +
// handler. Start() registers, subscribes, and spins the consume + scheduler
// loops; Stop(timeout) runs the §1.6 graceful-stop sequence.
type Worker struct {
	cfg       Config
	transport Transport
	consumer  Consumer
	handler   TaskHandler
	validator *SensitiveValidator
	fsm       *FSM
	registry  *InFlightRegistry
	pipeline  *MessagePipeline
	logger    *log.Logger

	heartbeat *HeartbeatScheduler
	lease     *LeaseRenewalScheduler

	// rootCtx governs all loops; rootCancel wakes them on Stop.
	rootCtx    context.Context
	rootCancel context.CancelFunc

	wg       sync.WaitGroup // consume + scheduler goroutines
	taskWg   sync.WaitGroup // in-flight handler executions
	draining atomic.Bool
	stopOnce sync.Once
	started  atomic.Bool
}

// NewWorker builds a worker. consumer + handler are required; validator
// defaults to a protocol-keyword validator when nil.
func NewWorker(cfg Config, transport Transport, consumer Consumer, handler TaskHandler, validator *SensitiveValidator, logger *log.Logger) *Worker {
	cfg = cfg.withDefaults()
	if logger == nil {
		logger = log.Default()
	}
	if validator == nil {
		validator = NewSensitiveValidator()
	}
	fsm := NewFSM()
	registry := NewInFlightRegistry()
	w := &Worker{
		cfg:       cfg,
		transport: transport,
		consumer:  consumer,
		handler:   handler,
		validator: validator,
		fsm:       fsm,
		registry:  registry,
		logger:    logger,
	}
	w.pipeline = NewMessagePipeline(cfg.TenantID, fsm, cfg.MaxConcurrentTasks, registry.Count, logger)
	w.heartbeat = NewHeartbeatScheduler(transport, fsm, cfg.WorkerCode, cfg.TenantID,
		WithHeartbeatInterval(cfg.HeartbeatInterval),
		WithHeartbeatInFlight(registry.Count),
		WithHeartbeatLogger(logger),
		WithOnDrain(w.beginDrain),
	)
	w.lease = NewLeaseRenewalScheduler(transport, registry, cfg.WorkerCode, cfg.TenantID,
		WithLeaseInterval(cfg.LeaseRenewInterval),
		WithLeaseLogger(logger),
	)
	return w
}

// FSM exposes the runtime FSM (tests / observability).
func (w *Worker) FSM() *FSM { return w.fsm }

// InFlight returns the current in-flight task count.
func (w *Worker) InFlight() int { return w.registry.Count() }

// Heartbeat exposes the heartbeat scheduler (tests).
func (w *Worker) Heartbeat() *HeartbeatScheduler { return w.heartbeat }

// Start registers the worker, applies the register-online decision, and spins
// the consume loop + both schedulers. Returns a register error (fatal auth /
// sensitive-data rejection) without starting loops.
func (w *Worker) Start(ctx context.Context) error {
	req := RegisterRequest{
		WorkerCode:     w.cfg.WorkerCode,
		TenantID:       w.cfg.TenantID,
		BuildID:        w.cfg.BuildID,
		SDKVersion:     w.cfg.SDKVersion,
		CapabilityTags: w.cfg.CapabilityTags,
		Attributes:     w.cfg.RegisterAttributes,
	}
	// §1.8 register-path sensitive scan (fail-fast at startup).
	if err := w.validator.ValidateRegister(req); err != nil {
		return err
	}
	res, err := w.transport.Register(ctx, req)
	if err != nil {
		return err
	}

	// Apply register-online decision: FSM -> NORMAL, start schedulers, subscribe.
	d := protocol.DecideRegister(res.Idempotent)
	if d.FsmTransition != nil {
		w.fsm.SetState(WorkerState(*d.FsmTransition))
	}
	w.logger.Printf("INFO worker registered code=%s idempotent=%v state=%s",
		w.cfg.WorkerCode, res.Idempotent, w.fsm.State())

	w.rootCtx, w.rootCancel = context.WithCancel(ctx)
	w.started.Store(true)

	w.wg.Add(3)
	go func() { defer w.wg.Done(); w.heartbeat.Run(w.rootCtx) }()
	go func() { defer w.wg.Done(); w.lease.Run(w.rootCtx) }()
	go func() { defer w.wg.Done(); w.consumeLoop(w.rootCtx) }()
	return nil
}

// consumeLoop polls the consumer and dispatches accepted records to handlers.
// It honors draining (rejects new work) and the FSM Kafka pause flag.
func (w *Worker) consumeLoop(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		if w.draining.Load() {
			return // §1.6: stop accepting new messages.
		}
		records, ok := w.consumer.Poll()
		if !ok {
			return // woken / closed.
		}
		for _, rec := range records {
			if w.draining.Load() || ctx.Err() != nil {
				return
			}
			msg, disp := w.pipeline.OnMessage(rec)
			switch disp {
			case DispositionAccepted:
				w.dispatch(ctx, msg)
				w.consumer.Commit()
			case DispositionBackpressure:
				// partition paused; leave offset uncommitted, retry later.
				w.consumer.Pause()
			case DispositionRejectedSchema, DispositionDroppedForeignTenant, DispositionDecodeError:
				// do NOT commit offset (§1.9 / §A); drop and move on.
			}
		}
	}
}

// dispatch claims, validates parameters, runs the handler, and reports the
// terminal result. Runs the handler in a goroutine bounded by maxConcurrent via
// the in-flight registry (backpressure gate already enforced upstream).
func (w *Worker) dispatch(ctx context.Context, msg TaskDispatchMessage) {
	claim, err := w.transport.Claim(ctx, msg.TaskID, msg.IdempotencyKey)
	if err != nil {
		if _, ok := errAsDrop(err); ok {
			w.logger.Printf("WARN claim dropped taskId=%s: %v", msg.TaskID, err)
			return
		}
		w.logger.Printf("WARN claim failed taskId=%s: %v", msg.TaskID, err)
		return
	}
	if claim.Idempotent {
		// 409: already claimed by another worker; treat as no-op success (§B).
		w.logger.Printf("INFO claim idempotent (already claimed) taskId=%s", msg.TaskID)
		return
	}

	sig := NewCancellationSignal(ctx)
	w.registry.Add(msg.TaskID, sig)
	w.taskWg.Add(1)
	go func() {
		defer w.taskWg.Done()
		defer w.registry.Remove(msg.TaskID)
		defer func() {
			// resume partition once a slot frees if we were paused.
			if w.fsm.Paused() && w.registry.Count() <= w.cfg.MaxConcurrentTasks {
				w.fsm.SetPaused(false)
				w.consumer.Resume()
			}
		}()

		// §1.8 parameters-path sensitive scan -> SECURITY_REJECTED.
		if res, rejected := w.validator.ValidateParameters(claim.EffectiveConfig); rejected {
			w.report(ctx, msg, res)
			return
		}

		tc := &TaskContext{
			TaskID:          msg.TaskID,
			EffectiveConfig: claim.EffectiveConfig,
			TraceID:         claim.TraceID,
			Cancellation:    sig,
			Progress:        NoopProgressReporter{},
		}
		result := w.handler.Execute(tc)
		// If cancelled and handler didn't set a terminal cancel code, normalize.
		if sig.IsCancellationRequested() && result.IsSuccess() {
			result = Fail(protocol.ErrorCodeCancelled, "task cancelled")
		}
		w.report(ctx, msg, result)
	}()
}

// report posts the terminal result with the dispatch idempotency key.
func (w *Worker) report(ctx context.Context, msg TaskDispatchMessage, result TaskResult) {
	if err := w.transport.Report(ctx, msg.TaskID, msg.IdempotencyKey, result); err != nil {
		w.logger.Printf("WARN report failed taskId=%s code=%s: %v", msg.TaskID, result.ErrorCode, err)
	}
}

// errAsDrop reports whether the error is a 404 (drop-and-forget).
func errAsDrop(err error) (*NotFoundError, bool) {
	var nf *NotFoundError
	if asNotFound(err, &nf) {
		return nf, true
	}
	return nil, false
}

// beginDrain flips the worker into DRAINING and triggers Stop with the
// configured budget (invoked from the heartbeat DRAINING directive, §1.5).
func (w *Worker) beginDrain() {
	w.fsm.SetState(StateDraining)
	go w.Stop(w.cfg.StopTimeout)
}

// Stop runs the §1.6 graceful-stop sequence exactly once:
//
//	draining=true (reject new) -> consumer.Wakeup() -> drain in-flight (timeout*0.4)
//	-> executor shutdown wait (timeout*0.6) -> Deactivate.
//
// PlanStop captures the plan (kafka=wakeup, deactivate, drainThenDeactivate).
func (w *Worker) Stop(timeout time.Duration) {
	w.stopOnce.Do(func() {
		if !w.started.Load() {
			return
		}
		plan := protocol.PlanStop(int(timeout / time.Millisecond))
		w.logger.Printf("INFO graceful stop worker=%s within=%dms", w.cfg.WorkerCode, *plan.WithinMs)

		w.draining.Store(true) // reject new Kafka messages immediately.
		w.fsm.SetState(StateDraining)

		// wake the consumer poll so consumeLoop exits.
		w.consumer.Wakeup()

		// stop schedulers + consume loop.
		if w.rootCancel != nil {
			w.rootCancel()
		}

		drainBudget := time.Duration(float64(timeout) * 0.4)
		execBudget := time.Duration(float64(timeout) * 0.6)

		// phase 1: wait for in-flight to drain within 40% of the budget.
		w.waitInFlight(drainBudget)
		// phase 2: wait for handler goroutines to finish within 60%.
		w.waitTasks(execBudget)
		// scheduler/consume goroutines are bounded too; don't block forever.
		w.wg.Wait()

		w.consumer.Close()

		// §1.6 final step: say goodbye. Best-effort with a short bounded ctx.
		dctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := w.transport.Deactivate(dctx, w.cfg.WorkerCode); err != nil {
			w.logger.Printf("WARN deactivate failed worker=%s: %v", w.cfg.WorkerCode, err)
		} else {
			w.logger.Printf("INFO worker deactivated code=%s", w.cfg.WorkerCode)
		}
	})
}

// waitInFlight blocks until the in-flight count hits zero or the budget expires.
func (w *Worker) waitInFlight(budget time.Duration) {
	deadline := time.Now().Add(budget)
	for w.registry.Count() > 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
}

// waitTasks waits for handler goroutines within the budget.
func (w *Worker) waitTasks(budget time.Duration) {
	done := make(chan struct{})
	go func() { w.taskWg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(budget):
		w.logger.Printf("WARN executor shutdown timed out worker=%s, %d task(s) still running",
			w.cfg.WorkerCode, w.registry.Count())
	}
}

// RunUntilSignal starts the worker and blocks until SIGINT/SIGTERM, then runs
// Stop(30s) (byo-sdk-guide §1.6 K8s terminationGracePeriodSeconds=30s).
func (w *Worker) RunUntilSignal(ctx context.Context) error {
	sigCtx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := w.Start(sigCtx); err != nil {
		return err
	}
	<-sigCtx.Done()
	w.logger.Printf("INFO signal received worker=%s -> Stop(30s)", w.cfg.WorkerCode)
	w.Stop(30 * time.Second)
	return nil
}
