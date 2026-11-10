// Package kafka 基于 github.com/segmentio/kafka-go 实现自托管 Worker SDK 的消费适配器。
//
// 本包使用独立嵌套模块，SDK 核心仅依赖 client.Consumer 接口；使用 Kafka 的调用方
// 按需引入本模块，自行提供消息代理适配器的调用方无需引入。
//
// 消费行为遵循 byo-sdk-guide.md §1.2、§1.5 和 §1.9：
//   - 按任务类型和 Worker 编码发现定向派单主题，使用租户和 Worker 专属消费组。
//   - 按配置启用 SASL/SCRAM-SHA-512 和 TLS；未配置凭据时使用明文连接。
//   - 反序列化消息后交给 client.MessagePipeline，执行协议版本、租户和容量检查。
//   - 认领或终结处置明确后手动提交位点；被拒绝消息按分区保留位置并等待重投。
package kafka

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/pinpols/file-batch-system/sdk/go/client"
	kgo "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/sasl/scram"
)

// DefaultTopicRefreshInterval 控制重新发现派单主题的周期。
// kafka-go 消费组创建时需要固定主题集合，适配器通过定期查询元数据发现匹配主题，
// 并在主题集合变化时重建消费组。
const DefaultTopicRefreshInterval = 5 * time.Minute

// DefaultFetchMaxWait bounds how long a single underlying FetchMessage blocks
// before returning so Poll() stays responsive to Wakeup()/Pause().
const DefaultFetchMaxWait = 1 * time.Second

// 未确认消息重读前的间隔，避免持续失败时频繁重试。
const withholdRetryDelay = 2 * time.Second

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

// dispatchTopicBase is the common prefix of every platform dispatch topic.
const dispatchTopicBase = "batch.task.dispatch."

// directDispatchSuffix returns the ".node.<workerCode>" suffix that the platform
// appends when it directs a task at THIS specific worker (sticky / group-gated
// routing for SDK self-hosted workers, ADR-035 §2). The orchestrator publishes
// SDK-bound tasks to `batch.task.dispatch.<workerType>.node.<workerCode>` — the
// canonical scheme used by built-in workers' AbstractTaskConsumer.topicPattern()
// (base-first), NOT the old `batch.task.dispatch.<tenant>.*` (tenant-first) which
// the orchestrator never publishes to. Cross-tenant safety is still enforced by
// the pipeline tenant self-check (§1.9) on each decoded message.
func (c Config) directDispatchSuffix() string {
	return ".node." + c.WorkerCode
}

// matchesDirectDispatch reports whether a broker topic is THIS worker's
// node-direct dispatch topic: batch.task.dispatch.<workerType>.node.<workerCode>.
// It is intentionally <workerType>-agnostic — an SDK self-hosted worker may serve
// any task type, and the platform dispatches each to its own base (import / export
// / process / dispatch / atomic) then appends `.node.<workerCode>`. Matching only
// the base prefix + node suffix means one subscription covers every task type.
func matchesDirectDispatch(topicName, workerCode string) bool {
	return strings.HasPrefix(topicName, dispatchTopicBase) &&
		strings.HasSuffix(topicName, ".node."+workerCode)
}

// groupID returns the per-worker consumer group g-sdk-<tenantId>-<workerCode>.
func (c Config) groupID() string {
	return "g-sdk-" + c.TenantID + "-" + c.WorkerCode
}

// Consumer is the kafka-go-backed implementation of client.Consumer.
//
// Pause/Resume 控制向业务消费循环交付记录；分区 reader 最多缓存有限记录。
// 已拉取记录仍须由当前代际的 Commit 确认，重平衡后由 broker 提交位置恢复。
type Consumer struct {
	cfg    Config
	logger *log.Logger
	dialer *kgo.Dialer
	client *kgo.Client

	mu     sync.Mutex
	topics []string
	paused bool
	woken  bool
	group  *kgo.ConsumerGroup
	active *delivery
	events chan *delivery
	done   chan struct{}

	// pending 保留交付给消费循环、尚未判定的单条记录。
	pending []kgo.Message

	// 记录当前代际中被暂扣的位置；每个分区不会在确认前交付后续记录。
	withheld map[tpKey]int64
	// 每个待处理 offset 单独保留，重读成功时只解除对应记录。
	withheldOffsets map[tpKey]map[int64]struct{}

	resumeCh chan struct{} // signaled on Resume()/Wakeup() to unpark a paused Poll
	ctx      context.Context
	cancel   context.CancelFunc
}

// 每个分区至多有一条记录等待消费循环判定；代际结束后不得继续处理或提交。
type delivery struct {
	message    kgo.Message
	ctx        context.Context
	generation offsetCommitter
	decision   chan bool
}

type offsetCommitter interface {
	CommitOffsets(map[string]map[int]int64) error
}

// tpKey identifies a topic-partition for the per-partition commit ceiling.
type tpKey struct {
	topic     string
	partition int
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
		events:   make(chan *delivery),
		done:     make(chan struct{}),
		ctx:      ctx,
		cancel:   cancel,
		withheld: map[tpKey]int64{},
	}

	topics, err := c.discoverTopics()
	if err != nil {
		cancel()
		return nil, err
	}
	if len(topics) == 0 {
		cancel()
		return nil, fmt.Errorf("kafka: no topics match suffix %q (the node-direct dispatch topic batch.task.dispatch.<workerType>.node.<workerCode> must exist; the orchestrator creates it on first dispatch, or pre-create it)", cfg.directDispatchSuffix())
	}
	c.topics = topics
	go c.runConsumerGroup()
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
	var matched []string
	for _, t := range resp.Topics {
		if t.Error != nil {
			continue
		}
		if matchesDirectDispatch(t.Name, c.cfg.WorkerCode) {
			matched = append(matched, t.Name)
		}
	}
	return matched, nil
}

// runConsumerGroup 管理分配代际和主题发现；每次重建均等待旧组退出。
func (c *Consumer) runConsumerGroup() {
	defer close(c.done)
	interval := c.cfg.TopicRefreshInterval
	if interval <= 0 {
		interval = DefaultTopicRefreshInterval
	}
	for c.ctx.Err() == nil {
		c.mu.Lock()
		topics := append([]string(nil), c.topics...)
		c.mu.Unlock()
		group, err := kgo.NewConsumerGroup(kgo.ConsumerGroupConfig{
			ID: c.cfg.groupID(), Brokers: c.cfg.Brokers, Topics: topics, Dialer: c.dialer,
		})
		if err != nil {
			c.logger.Printf("ERROR kafka consumer group creation failed: %v", err)
			return
		}
		c.mu.Lock()
		c.group = group
		c.mu.Unlock()
		refreshAt := time.Now().Add(interval)
		for c.ctx.Err() == nil {
			wait, cancel := context.WithDeadline(c.ctx, refreshAt)
			gen, nextErr := group.Next(wait)
			cancel()
			if nextErr != nil {
				if c.ctx.Err() != nil {
					break
				}
				if nextErr == context.DeadlineExceeded {
					refreshAt = time.Now().Add(interval)
					updated, discoverErr := c.discoverTopics()
					if discoverErr != nil {
						c.logger.Printf("WARN kafka topic refresh failed: %v", discoverErr)
					} else if len(updated) > 0 && !sameTopics(updated, topics) {
						c.mu.Lock()
						c.topics = updated
						c.mu.Unlock()
						break
					}
					continue
				}
				if nextErr == kgo.ErrGroupClosed {
					break
				}
				c.logger.Printf("WARN kafka consumer group error: %v", nextErr)
				continue
			}
			c.mu.Lock()
			c.withheld = map[tpKey]int64{}
			c.withheldOffsets = nil
			c.mu.Unlock()
			for topic, assignments := range gen.Assignments {
				for _, assignment := range assignments {
					topic, assignment := topic, assignment
					gen.Start(func(ctx context.Context) { c.readPartition(ctx, gen, topic, assignment) })
				}
			}
		}
		_ = group.Close()
		c.mu.Lock()
		if c.group == group {
			c.group = nil
		}
		c.mu.Unlock()
	}
}

// 每个分区只预取一条并等待结果；未确认时仅重试该分区。
func (c *Consumer) readPartition(ctx context.Context, gen *kgo.Generation, topic string, assignment kgo.PartitionAssignment) {
	maxWait := c.cfg.FetchMaxWait
	if maxWait <= 0 {
		maxWait = DefaultFetchMaxWait
	}
	reader := kgo.NewReader(kgo.ReaderConfig{
		Brokers: c.cfg.Brokers, Topic: topic, Partition: assignment.ID,
		Dialer: c.dialer, MaxWait: maxWait, QueueCapacity: 1,
	})
	defer reader.Close()
	if err := reader.SetOffset(assignment.Offset); err != nil {
		c.logger.Printf("ERROR kafka partition offset setup failed: %v", err)
		return
	}
	for ctx.Err() == nil && c.ctx.Err() == nil {
		message, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil || c.ctx.Err() != nil {
				return
			}
			c.logger.Printf("WARN kafka partition fetch failed: %v", err)
			timer := time.NewTimer(time.Second)
			select {
			case <-timer.C:
			case <-ctx.Done():
			case <-c.ctx.Done():
			}
			timer.Stop()
			continue
		}
		item := &delivery{message: message, ctx: ctx, generation: gen, decision: make(chan bool, 1)}
		for {
			select {
			case c.events <- item:
			case <-ctx.Done():
				return
			case <-c.ctx.Done():
				return
			}
			var committed bool
			select {
			case committed = <-item.decision:
			case <-ctx.Done():
				return
			case <-c.ctx.Done():
				return
			}
			if committed {
				break
			}
			timer := time.NewTimer(withholdRetryDelay)
			select {
			case <-timer.C:
			case <-ctx.Done():
				timer.Stop()
				return
			case <-c.ctx.Done():
				timer.Stop()
				return
			}
			timer.Stop()
		}
	}
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

// Poll 返回一个仍属于当前消费组代际的分区记录。
func (c *Consumer) Poll() ([]client.Record, bool) {
	for {
		c.mu.Lock()
		if c.woken || c.ctx.Err() != nil {
			c.mu.Unlock()
			return nil, false
		}
		if c.paused {
			c.mu.Unlock()
			select {
			case <-c.resumeCh:
				continue
			case <-c.ctx.Done():
				return nil, false
			}
		}
		c.mu.Unlock()
		select {
		case <-c.ctx.Done():
			return nil, false
		case <-c.resumeCh:
			continue
		case item := <-c.events:
			for item.ctx.Err() == nil {
				c.mu.Lock()
				if c.woken || c.ctx.Err() != nil {
					c.mu.Unlock()
					return nil, false
				}
				if !c.paused {
					c.active = item
					c.pending = []kgo.Message{item.message}
					c.mu.Unlock()
					return []client.Record{{Topic: item.message.Topic, Key: string(item.message.Key), Value: item.message.Value}}, true
				}
				c.mu.Unlock()
				select {
				case <-c.resumeCh:
				case <-item.ctx.Done():
				case <-c.ctx.Done():
					return nil, false
				}
			}
		case <-time.After(c.fetchTimeout()):
			return nil, true
		}
	}
}

func (c *Consumer) fetchTimeout() time.Duration {
	w := c.cfg.FetchMaxWait
	if w <= 0 {
		w = DefaultFetchMaxWait
	}
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

// Commit 只提交当前代际内实际受理的分区位置；失败时保留该记录重试。
func (c *Consumer) Commit() {
	c.mu.Lock()
	item := c.active
	c.active = nil
	c.pending = nil
	c.mu.Unlock()
	if item == nil || item.ctx.Err() != nil {
		return
	}
	m := item.message
	err := item.generation.CommitOffsets(map[string]map[int]int64{m.Topic: {m.Partition: m.Offset + 1}})
	if err != nil {
		c.logger.Printf("ERROR kafka commit offsets failed (will redeliver): %v", err)
	}
	if err == nil {
		c.mu.Lock()
		key := tpKey{topic: m.Topic, partition: m.Partition}
		delete(c.withheld, key)
		delete(c.withheldOffsets, key)
		c.mu.Unlock()
	}
	select {
	case item.decision <- err == nil:
	case <-item.ctx.Done():
	case <-c.ctx.Done():
	}
}

// DropPending 放弃当前记录并在原分区重试，不推进提交位置。
func (c *Consumer) DropPending() { c.Withhold() }

// Withhold 仅阻塞当前分区，原代际失效时由 broker 的提交位置恢复。
func (c *Consumer) Withhold() {
	c.mu.Lock()
	if len(c.pending) == 0 {
		c.mu.Unlock()
		return
	}
	m := c.pending[len(c.pending)-1]
	key := tpKey{topic: m.Topic, partition: m.Partition}
	if c.withheldOffsets == nil {
		c.withheldOffsets = make(map[tpKey]map[int64]struct{})
	}
	if c.withheldOffsets[key] == nil {
		c.withheldOffsets[key] = make(map[int64]struct{})
	}
	c.withheldOffsets[key][m.Offset] = struct{}{}
	if cur, ok := c.withheld[key]; !ok || m.Offset < cur {
		c.withheld[key] = m.Offset
	}
	item := c.active
	c.active = nil
	c.pending = nil
	c.mu.Unlock()
	if item != nil {
		select {
		case item.decision <- false:
		case <-item.ctx.Done():
		case <-c.ctx.Done():
		}
	}
}

// committable returns the subset of msgs whose offset is strictly below their
// partition's withheld ceiling (or whose partition has no ceiling). It is pure
// (no receiver, no IO) so the offset-crossing invariant is unit-testable without
// a broker. Order is preserved.
func committable(msgs []kgo.Message, withheld map[tpKey]int64) []kgo.Message {
	if len(withheld) == 0 {
		return msgs
	}
	out := msgs[:0:0] // fresh backing array; never alias the caller's slice
	for _, m := range msgs {
		if ceil, ok := withheld[tpKey{topic: m.Topic, partition: m.Partition}]; ok && m.Offset >= ceil {
			continue // at/past the withheld ceiling → never commit
		}
		out = append(out, m)
	}
	return out
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
	c.cancel()
	<-c.done
}

func (c *Consumer) signalResume() {
	select {
	case c.resumeCh <- struct{}{}:
	default:
	}
}
