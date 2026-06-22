package client

import (
	"encoding/json"
	"log"
	"strings"
	"sync"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// Record is a raw consumed dispatch message (one Kafka record). Value is the
// JSON payload; the real Kafka adapter (a future Consumer impl) fills these.
type Record struct {
	Topic string
	Key   string
	Value []byte
}

// TaskDispatchMessage is the decoded dispatch payload (TaskDispatchMessage
// schema; unknown fields ignored per encoding/json default — wire-protocol §A).
type TaskDispatchMessage struct {
	TaskID            string         `json:"taskId"`
	TenantID          string         `json:"tenantId"`
	SchemaVersion     string         `json:"schemaVersion"`
	WorkerType        string         `json:"workerType"`
	IdempotencyKey    string         `json:"idempotencyKey"`
	RuntimeAttributes map[string]any `json:"runtimeAttributes"`
	Parameters        map[string]any `json:"parameters"`
}

// UnmarshalJSON decodes the dispatch payload, binding the routing key from the
// v2 field `workerType` and falling back to the v1 legacy name `taskType`.
// encoding/json has no alias support (a tag binds one name only), so the v1
// fallback is done explicitly — keeping cross-language parity with Java's
// @JsonAlias("taskType") and Rust's serde alias. Platform v2 only emits
// `workerType`; the fallback is belt-and-suspenders. Guard:
// consumer_test.go TestPipeline_WorkerTypeBinding_*.
func (m *TaskDispatchMessage) UnmarshalJSON(data []byte) error {
	type alias TaskDispatchMessage // shed methods to avoid recursion
	aux := struct {
		*alias
		TaskType string `json:"taskType"`
		// taskId is a BIGINT on the platform, so the orchestrator emits it as a JSON
		// number (e.g. 4515), but the SDK carries it as a string (URL path segment for
		// claim/report). Capture it raw and normalize, tolerating both number and the
		// quoted-string form the contract fixtures historically used. Without this the
		// real dispatch payload fails to decode ("cannot unmarshal number into string").
		TaskID json.RawMessage `json:"taskId"`
	}{alias: (*alias)(m)}
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	m.TaskID = strings.Trim(strings.TrimSpace(string(aux.TaskID)), `"`)
	if m.WorkerType == "" && aux.TaskType != "" {
		m.WorkerType = aux.TaskType
	}
	return nil
}

// MessageDisposition is the outcome of the OnMessage pipeline for one record.
type MessageDisposition string

const (
	// DispositionAccepted — message passed all gates; ready to claim+execute.
	DispositionAccepted MessageDisposition = "ACCEPTED"
	// DispositionRejectedSchema — unknown schemaVersion major (§A); offset NOT committed.
	DispositionRejectedSchema MessageDisposition = "REJECTED_SCHEMA"
	// DispositionDroppedForeignTenant — tenant self-check failed (§1.9); dropped.
	DispositionDroppedForeignTenant MessageDisposition = "DROPPED_FOREIGN_TENANT"
	// DispositionDecodeError — payload could not be parsed (poison); offset IS
	// committed (commit-skip) to advance past it, else one corrupt record would
	// head-of-line block the partition forever. fixture 30 / parity §4.5.
	DispositionDecodeError MessageDisposition = "DECODE_ERROR"
	// DispositionBackpressure — at capacity; partition paused, message resumed later (§1.5/§2).
	DispositionBackpressure MessageDisposition = "BACKPRESSURE"
)

// Consumer is the message-source SPI. A real Kafka client (segmentio /
// confluent) plugs in here as a future adapter; the SDK core depends only on
// this interface and never imports a broker library.
type Consumer interface {
	// Poll returns the next batch of records (blocking up to its own timeout) or
	// an empty slice. Returns false when the consumer has been woken/closed.
	Poll() ([]Record, bool)
	// Pause/Resume mirror Kafka assignment.pause()/resume() for backpressure.
	Pause()
	Resume()
	// Commit acknowledges processed offsets.
	Commit()
	// Wakeup interrupts an in-progress Poll (used on graceful stop, §1.6).
	Wakeup()
	// Close releases resources.
	Close()
}

// MessagePipeline runs the per-record gate chain (byo-sdk-guide §1.2/§1.5/§1.9):
// decode -> schemaVersion classify -> tenant self-check -> backpressure.
// It is broker-agnostic: it operates on an already-consumed Record.
type MessagePipeline struct {
	tenantID string
	fsm      *FSM
	logger   *log.Logger
	// inFlight / maxConcurrent drive backpressure; supplied via funcs so the
	// live worker can report real counts.
	inFlight      func() int
	maxConcurrent int
}

// NewMessagePipeline builds a pipeline bound to the worker's tenant + FSM.
func NewMessagePipeline(tenantID string, fsm *FSM, maxConcurrent int, inFlight func() int, logger *log.Logger) *MessagePipeline {
	if logger == nil {
		logger = log.Default()
	}
	if inFlight == nil {
		inFlight = func() int { return 0 }
	}
	return &MessagePipeline{
		tenantID:      tenantID,
		fsm:           fsm,
		logger:        logger,
		inFlight:      inFlight,
		maxConcurrent: maxConcurrent,
	}
}

// OnMessage runs one record through the gate chain. The decoded message is
// returned on DispositionAccepted (and on DispositionBackpressure, since the
// message is valid but deferred). Offset-commit policy is the CALLER's (see
// lifecycle consume loop), keyed on disposition: Accepted + DecodeError commit
// (the latter commit-skip past poison); RejectedSchema / DroppedForeignTenant /
// Backpressure withhold (valid-but-deferred, redeliverable per §1.9 / §A).
func (p *MessagePipeline) OnMessage(rec Record) (TaskDispatchMessage, MessageDisposition) {
	var msg TaskDispatchMessage
	if err := json.Unmarshal(rec.Value, &msg); err != nil {
		p.logger.Printf("ERROR decode dispatch message topic=%s: %v", rec.Topic, err)
		return msg, DispositionDecodeError
	}

	// §A schemaVersion compatibility: unknown major -> reject, do not commit.
	if protocol.ClassifySchemaVersion(msg.SchemaVersion) == "reject" {
		p.logger.Printf("ERROR reject unknown schemaVersion=%q taskId=%s (not committing offset)",
			msg.SchemaVersion, msg.TaskID)
		return msg, DispositionRejectedSchema
	}

	// §1.9 tenant self-check: last line of defense against ACL drift.
	if msg.TenantID != p.tenantID {
		p.logger.Printf("ERROR foreign-tenant message dropped: msgTenant=%q wantTenant=%q taskId=%s",
			msg.TenantID, p.tenantID, msg.TaskID)
		return msg, DispositionDroppedForeignTenant
	}

	// §1.5/§2 capacity backpressure: pause partition when in-flight is full.
	// On the receive path in-flight is at/above max (we just got a new message),
	// so this only ever yields pause; the hysteresis resume path runs as a slot
	// frees (see lifecycle.go), where the decision core gates resume on max/2.
	d := protocol.DecideBackpressure(p.inFlight(), p.maxConcurrent, p.fsm.Paused())
	if d.Action == "backpressure" {
		p.fsm.ApplyKafkaDirective(d.Kafka) // sets paused=true
		p.logger.Printf("INFO backpressure: inFlight=%d max=%d paused partition taskId=%s",
			p.inFlight(), p.maxConcurrent, msg.TaskID)
		return msg, DispositionBackpressure
	}

	return msg, DispositionAccepted
}

// ---------------------------------------------------------------------------
// FakeConsumer — scripted records, no broker (testkit core).
// ---------------------------------------------------------------------------

// FakeConsumer feeds scripted records and records pause/resume/commit/wakeup
// calls. Used by tests in place of a real Kafka client. All methods are
// goroutine-safe because the live worker calls Wakeup()/Close() from Stop while
// the consume loop is still polling — mirroring a real Kafka client's
// thread-safe wakeup contract.
type FakeConsumer struct {
	mu      sync.Mutex
	records [][]Record
	idx     int
	paused  bool
	woken   bool
	Commits int
	Pauses  int
	Resumes int
	Wakeups int
	Closed  bool
}

// NewFakeConsumer scripts successive Poll() batches.
func NewFakeConsumer(batches ...[]Record) *FakeConsumer {
	return &FakeConsumer{records: batches}
}

// Feed appends one more scripted batch.
func (f *FakeConsumer) Feed(batch []Record) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.records = append(f.records, batch)
}

// Poll returns the next scripted batch; ok=false once exhausted or woken.
func (f *FakeConsumer) Poll() ([]Record, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.woken {
		return nil, false
	}
	if f.idx >= len(f.records) {
		return nil, false
	}
	batch := f.records[f.idx]
	f.idx++
	return batch, true
}

// Pause records a pause call.
func (f *FakeConsumer) Pause() { f.mu.Lock(); defer f.mu.Unlock(); f.paused = true; f.Pauses++ }

// Resume records a resume call.
func (f *FakeConsumer) Resume() { f.mu.Lock(); defer f.mu.Unlock(); f.paused = false; f.Resumes++ }

// IsPaused reports the fake's pause flag.
func (f *FakeConsumer) IsPaused() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.paused }

// Commit records a commit call.
func (f *FakeConsumer) Commit() { f.mu.Lock(); defer f.mu.Unlock(); f.Commits++ }

// Wakeup records a wakeup and short-circuits further polls.
func (f *FakeConsumer) Wakeup() { f.mu.Lock(); defer f.mu.Unlock(); f.woken = true; f.Wakeups++ }

// Close records a close call.
func (f *FakeConsumer) Close() { f.mu.Lock(); defer f.mu.Unlock(); f.Closed = true }

// counters returns synchronized snapshots for test assertions.
func (f *FakeConsumer) wakeups() int { f.mu.Lock(); defer f.mu.Unlock(); return f.Wakeups }
func (f *FakeConsumer) closed() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.Closed }
func (f *FakeConsumer) commits() int { f.mu.Lock(); defer f.mu.Unlock(); return f.Commits }
