// Package kafka is the real Kafka consumer adapter for the BYO worker SDK,
// implementing the broker-agnostic client.Consumer SPI on top of
// github.com/segmentio/kafka-go.
//
// It lives in a NESTED module (see go.mod) so the root batch-worker-sdk-go
// module stays zero-dependency: the SDK core only knows the client.Consumer
// interface and never imports a Kafka library. Tenants who want real Kafka
// pull in this module; tenants who embed the SDK and bring their own broker
// glue do not.
//
// Wire behavior implemented here mirrors byo-sdk-guide.md §1.2 / §1.5 / §1.9:
//   - wildcard topic subscription batch.task.dispatch.<tenant>.*
//   - consumer group g-sdk-<tenantId>-<workerCode>
//   - SASL/SCRAM-SHA-512 + TLS support (config-driven; PLAINTEXT when no creds)
//   - JSON deserialize -> client.MessagePipeline (schemaVersion reject,
//     tenant self-check, capacity backpressure)
//   - MANUAL offset commit: commit ONLY after the pipeline accepts; never on
//     reject (unknown schemaVersion) or foreign-tenant drop -> redelivery
//   - partition pause/resume modeled by stopping/resuming the fetch loop
package kafka

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/client"
	kgo "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/sasl/scram"
)

// DefaultTopicRefreshInterval is how often the adapter re-lists broker topics to
// pick up newly created per-task-type dispatch topics
// (batch.task.dispatch.<tenant>.<taskType>). kafka-go's GroupReader takes a
// fixed GroupTopics slice at construction and does NOT support regex/wildcard
// subscriptions, so wildcard is emulated by periodic metadata polling +
// prefix-filtering + reader restart when the matched set changes.
const DefaultTopicRefreshInterval = 5 * time.Minute

// DefaultFetchMaxWait bounds how long a single underlying FetchMessage blocks
// before returning so Poll() stays responsive to Wakeup()/Pause().
const DefaultFetchMaxWait = 1 * time.Second

// Config drives the Kafka adapter. Credentials come from here (sourced by the
// caller from env / secret store) and are NEVER read from message payloads
// (byo-sdk-guide §1.2/§1.8).
type Config struct {
	// Brokers is the bootstrap broker list, e.g. ["localhost:19092"].
	Brokers []string
	// TenantID scopes the wildcard topic prefix and the consumer group, and is
	// the value the pipeline tenant self-check (§1.9) compares against.
	TenantID string
	// WorkerCode identifies this worker within the tenant; combined with
	// TenantID into the consumer group g-sdk-<tenantId>-<workerCode>.
	WorkerCode string

	// SASLUsername / SASLPassword enable SASL/SCRAM-SHA-512 when both are set.
	// When empty, the adapter connects over PLAINTEXT (local integration).
	SASLUsername string
	SASLPassword string

	// TLSConfig, when non-nil, wraps the connection in TLS. SASL/SCRAM in
	// production is normally paired with TLS; locally we use neither.
	TLSConfig *tls.Config

	// TopicRefreshInterval overrides DefaultTopicRefreshInterval when > 0.
	TopicRefreshInterval time.Duration
	// FetchMaxWait overrides DefaultFetchMaxWait when > 0.
	FetchMaxWait time.Duration

	// Logger is optional; defaults to log.Default().
	Logger *log.Logger
}

// topicPrefix returns the wildcard prefix batch.task.dispatch.<tenant>. that a
// topic must start with to belong to this tenant.
func (c Config) topicPrefix() string {
	return "batch.task.dispatch." + c.TenantID + "."
}

// groupID returns the per-worker consumer group g-sdk-<tenantId>-<workerCode>.
func (c Config) groupID() string {
	return "g-sdk-" + c.TenantID + "-" + c.WorkerCode
}

// Consumer is the kafka-go-backed implementation of client.Consumer.
//
// Pause/Resume have no native equivalent on kafka-go's GroupReader, so they are
// modeled by gating the fetch loop: while paused, Poll() blocks (parking until
// resumed or woken) instead of fetching, which stops new records from flowing —
// equivalent in effect to assignment.pause(). Offsets already fetched are not
// committed until Commit() is called after pipeline acceptance.
type Consumer struct {
	cfg    Config
	logger *log.Logger
	dialer *kgo.Dialer
	client *kgo.Client

	mu     sync.Mutex
	reader *kgo.Reader
	topics []string
	paused bool
	woken  bool

	// pending holds messages fetched-but-not-yet-committed, so Commit() can
	// acknowledge exactly the offsets the pipeline accepted.
	pending []kgo.Message

	resumeCh chan struct{} // signaled on Resume()/Wakeup() to unpark a paused Poll
	ctx      context.Context
	cancel   context.CancelFunc

	lastRefresh time.Time
}

// compile-time assertion that *Consumer satisfies the phase-2 SPI.
var _ client.Consumer = (*Consumer)(nil)

// NewConsumer builds the adapter, performs an initial topic discovery, and
// constructs the GroupReader. It returns an error if no matching topic exists
// yet or the broker is unreachable.
func NewConsumer(cfg Config) (*Consumer, error) {
	if len(cfg.Brokers) == 0 {
		return nil, fmt.Errorf("kafka: no brokers configured")
	}
	if cfg.TenantID == "" || cfg.WorkerCode == "" {
		return nil, fmt.Errorf("kafka: tenantID and workerCode are required")
	}
	logger := cfg.Logger
	if logger == nil {
		logger = log.Default()
	}

	dialer, err := buildDialer(cfg)
	if err != nil {
		return nil, err
	}

	transport, err := buildTransport(cfg)
	if err != nil {
		return nil, err
	}
	kc := &kgo.Client{
		Addr:      kgo.TCP(cfg.Brokers...),
		Transport: transport,
	}

	ctx, cancel := context.WithCancel(context.Background())
	c := &Consumer{
		cfg:      cfg,
		logger:   logger,
		dialer:   dialer,
		client:   kc,
		resumeCh: make(chan struct{}, 1),
		ctx:      ctx,
		cancel:   cancel,
	}

	topics, err := c.discoverTopics()
	if err != nil {
		cancel()
		return nil, err
	}
	if len(topics) == 0 {
		cancel()
		return nil, fmt.Errorf("kafka: no topics match prefix %q (create the dispatch topic first)", cfg.topicPrefix())
	}
	c.topics = topics
	c.reader = c.newReader(topics)
	c.lastRefresh = time.Now()
	logger.Printf("INFO kafka adapter subscribed group=%s topics=%v", cfg.groupID(), topics)
	return c, nil
}

// buildDialer returns a kafka-go Dialer configured with SASL/SCRAM-SHA-512 and
// TLS when credentials are supplied, or a plain dialer otherwise.
func buildDialer(cfg Config) (*kgo.Dialer, error) {
	d := &kgo.Dialer{
		Timeout:   10 * time.Second,
		DualStack: true,
	}
	if cfg.SASLUsername != "" && cfg.SASLPassword != "" {
		mech, err := scram.Mechanism(scram.SHA512, cfg.SASLUsername, cfg.SASLPassword)
		if err != nil {
			return nil, fmt.Errorf("kafka: build SCRAM-SHA-512 mechanism: %w", err)
		}
		d.SASLMechanism = mech
	}
	if cfg.TLSConfig != nil {
		d.TLS = cfg.TLSConfig
	}
	return d, nil
}

// buildTransport mirrors the dialer's auth settings for the metadata client
// (kgo.Client uses a Transport rather than a Dialer).
func buildTransport(cfg Config) (*kgo.Transport, error) {
	t := &kgo.Transport{}
	if cfg.SASLUsername != "" && cfg.SASLPassword != "" {
		mech, err := scram.Mechanism(scram.SHA512, cfg.SASLUsername, cfg.SASLPassword)
		if err != nil {
			return nil, fmt.Errorf("kafka: build SCRAM-SHA-512 transport mechanism: %w", err)
		}
		t.SASL = mech
	}
	if cfg.TLSConfig != nil {
		t.TLS = cfg.TLSConfig
	}
	return t, nil
}

// discoverTopics lists all broker topics and returns those matching the
// tenant's wildcard prefix. This is the wildcard-subscription workaround:
// kafka-go does NOT support regex topics on a GroupReader, so we resolve the
// concrete topic set from cluster metadata.
func (c *Consumer) discoverTopics() ([]string, error) {
	ctx, cancel := context.WithTimeout(c.ctx, 10*time.Second)
	defer cancel()
	resp, err := c.client.Metadata(ctx, &kgo.MetadataRequest{})
	if err != nil {
		return nil, fmt.Errorf("kafka: metadata request: %w", err)
	}
	prefix := c.cfg.topicPrefix()
	var matched []string
	for _, t := range resp.Topics {
		if t.Error != nil {
			continue
		}
		if strings.HasPrefix(t.Name, prefix) {
			matched = append(matched, t.Name)
		}
	}
	return matched, nil
}

// newReader constructs a GroupReader over the given concrete topic set with
// MANUAL commit (CommitInterval: 0) so offsets are only acknowledged via
// explicit CommitMessages after the pipeline accepts.
func (c *Consumer) newReader(topics []string) *kgo.Reader {
	maxWait := c.cfg.FetchMaxWait
	if maxWait <= 0 {
		maxWait = DefaultFetchMaxWait
	}
	return kgo.NewReader(kgo.ReaderConfig{
		Brokers:        c.cfg.Brokers,
		GroupID:        c.cfg.groupID(),
		GroupTopics:    topics,
		Dialer:         c.dialer,
		CommitInterval: 0, // MANUAL commit
		MaxWait:        maxWait,
	})
}

// maybeRefreshTopics periodically re-lists topics; if the matched set changed it
// rebuilds the reader so newly created <taskType> topics get consumed. Any
// uncommitted pending messages are dropped on rebuild (they will be redelivered
// since they were never committed), preserving at-least-once.
//
// It is called with c.mu held. The old reader is NOT closed here — closing a
// kafka-go reader can block (it leaves the group), and doing so under c.mu would
// stall a concurrent Wakeup()/Close()/Commit(). Instead the displaced reader is
// returned so the caller can Close() it AFTER releasing the lock.
func (c *Consumer) maybeRefreshTopics() (old *kgo.Reader) {
	interval := c.cfg.TopicRefreshInterval
	if interval <= 0 {
		interval = DefaultTopicRefreshInterval
	}
	if time.Since(c.lastRefresh) < interval {
		return nil
	}
	c.lastRefresh = time.Now()
	topics, err := c.discoverTopics()
	if err != nil {
		c.logger.Printf("WARN kafka topic refresh failed: %v", err)
		return nil
	}
	if len(topics) == 0 || sameTopics(topics, c.topics) {
		return nil
	}
	c.logger.Printf("INFO kafka topic set changed old=%v new=%v; rebuilding reader", c.topics, topics)
	old = c.reader
	c.reader = c.newReader(topics)
	c.topics = topics
	c.pending = nil
	return old
}

// sameTopics compares two topic sets ignoring order.
func sameTopics(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	seen := make(map[string]struct{}, len(a))
	for _, t := range a {
		seen[t] = struct{}{}
	}
	for _, t := range b {
		if _, ok := seen[t]; !ok {
			return false
		}
	}
	return true
}

// Poll fetches one record (kafka-go has no batch fetch; we return single-record
// batches) and returns it as a client.Record. It returns (nil, false) when the
// consumer has been woken/closed. While paused, it parks until Resume/Wakeup
// rather than fetching — the partition-pause emulation.
func (c *Consumer) Poll() ([]client.Record, bool) {
	for {
		c.mu.Lock()
		if c.woken {
			c.mu.Unlock()
			return nil, false
		}
		if c.paused {
			c.mu.Unlock()
			// Park until resumed or woken; do not fetch new records.
			select {
			case <-c.resumeCh:
				continue
			case <-c.ctx.Done():
				return nil, false
			}
		}
		oldReader := c.maybeRefreshTopics()
		reader := c.reader
		c.mu.Unlock()

		// Close the displaced reader outside the lock (it can block leaving the
		// group); a nil-check keeps the common no-refresh path allocation-free.
		if oldReader != nil {
			_ = oldReader.Close()
		}

		fetchCtx, cancel := context.WithTimeout(c.ctx, c.fetchTimeout())
		msg, err := reader.FetchMessage(fetchCtx)
		cancel()
		if err != nil {
			c.mu.Lock()
			woken := c.woken
			c.mu.Unlock()
			if woken || c.ctx.Err() != nil {
				return nil, false
			}
			// Timeout / transient: return an empty batch with ok=true so the
			// consume loop can re-check pause/wakeup state and poll again.
			return nil, true
		}

		c.mu.Lock()
		c.pending = append(c.pending, msg)
		c.mu.Unlock()

		return []client.Record{{
			Topic: msg.Topic,
			Key:   string(msg.Key),
			Value: msg.Value,
		}}, true
	}
}

func (c *Consumer) fetchTimeout() time.Duration {
	w := c.cfg.FetchMaxWait
	if w <= 0 {
		w = DefaultFetchMaxWait
	}
	// Give the fetch a little headroom beyond MaxWait.
	return w + 500*time.Millisecond
}

// Pause stops the fetch loop (no new records). In-flight commits still work.
func (c *Consumer) Pause() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.paused = true
}

// Resume unparks a paused Poll and lets fetching continue.
func (c *Consumer) Resume() {
	c.mu.Lock()
	c.paused = false
	c.mu.Unlock()
	c.signalResume()
}

// Commit acknowledges all pending (fetched-and-accepted) offsets. The live
// worker calls Commit() ONLY after the pipeline accepts; on reject / foreign
// tenant the caller must NOT call Commit for that record, so it is redelivered.
//
// Because Poll returns one record per call and the worker commits per accepted
// record, pending normally holds exactly the message just accepted. Any pending
// message left uncommitted at the next rebuild/close is redelivered.
func (c *Consumer) Commit() {
	c.mu.Lock()
	if len(c.pending) == 0 {
		c.mu.Unlock()
		return
	}
	msgs := c.pending
	c.pending = nil
	reader := c.reader
	c.mu.Unlock()

	ctx, cancel := context.WithTimeout(c.ctx, 10*time.Second)
	defer cancel()
	if err := reader.CommitMessages(ctx, msgs...); err != nil {
		c.logger.Printf("ERROR kafka commit offsets failed (will redeliver): %v", err)
		// Put them back so a later Commit can retry.
		c.mu.Lock()
		c.pending = append(msgs, c.pending...)
		c.mu.Unlock()
	}
}

// DropPending discards the not-yet-committed messages without committing, so the
// broker redelivers them. The worker calls this after a reject / foreign-tenant
// drop instead of Commit(), keeping the offset withheld (§1.9 / §A).
func (c *Consumer) DropPending() {
	c.mu.Lock()
	c.pending = nil
	c.mu.Unlock()
}

// Wakeup interrupts an in-progress Poll (graceful stop, §1.6). Idempotent and
// goroutine-safe.
func (c *Consumer) Wakeup() {
	c.mu.Lock()
	c.woken = true
	c.mu.Unlock()
	c.signalResume() // unpark a parked-paused Poll
	c.cancel()       // interrupt an in-flight FetchMessage
}

// Close releases the reader and metadata client.
func (c *Consumer) Close() {
	c.mu.Lock()
	reader := c.reader
	c.reader = nil
	c.mu.Unlock()
	c.cancel()
	if reader != nil {
		_ = reader.Close()
	}
}

// signalResume does a non-blocking send on resumeCh to wake a parked Poll.
func (c *Consumer) signalResume() {
	select {
	case c.resumeCh <- struct{}{}:
	default:
	}
}
