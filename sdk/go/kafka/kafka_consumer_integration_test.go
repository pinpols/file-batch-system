package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/sdk/go/client"
	kgo "github.com/segmentio/kafka-go"
)

// Integration test for the real Kafka adapter. ENV-GATED: a plain `go test ./...`
// without BATCH_SDK_KAFKA_BOOTSTRAP skips it, so the default test run is broker-free and
// stays green. Run against the local PLAINTEXT broker with:
//
//	BATCH_SDK_KAFKA_BOOTSTRAP=localhost:19092 go test ./...
//
// SASL/SCRAM-SHA-512 + TLS are config-supported (see Config / buildDialer) but
// the local broker is PLAINTEXT, so this test exercises the PLAINTEXT path; the
// SCRAM path is validated by construction (buildDialer) and code review.
func TestKafkaConsumer_Integration(t *testing.T) {
	bootstrap := os.Getenv("BATCH_SDK_KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("BATCH_SDK_KAFKA_BOOTSTRAP not set; skipping Kafka integration test (broker-free run)")
	}

	const tenant = "acme"
	// Unique suffix so concurrent / repeated runs don't collide.
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	workerCode := "w-" + suffix
	// node-direct topic the consumer discovers by suffix (.node.<workerCode>):
	// batch.task.dispatch.<workerType>.node.<workerCode> (#2; was tenant-first).
	topic := fmt.Sprintf("batch.task.dispatch.http.node.%s", workerCode)

	brokers := strings.Split(bootstrap, ",")

	// --- create topic + cleanup -------------------------------------------
	createTopic(t, brokers[0], topic)
	t.Cleanup(func() { deleteTopic(brokers[0], topic) })

	// --- build the adapter under test -------------------------------------
	logger := log.New(os.Stderr, "[itest] ", log.LstdFlags)
	consumer, err := NewConsumer(Config{
		Brokers:    brokers,
		TenantID:   tenant,
		WorkerCode: workerCode,
		// Short refresh so the wildcard discovery is exercised quickly.
		TopicRefreshInterval: 2 * time.Second,
		FetchMaxWait:         500 * time.Millisecond,
		Logger:               logger,
	})
	if err != nil {
		t.Fatalf("NewConsumer: %v", err)
	}
	t.Cleanup(consumer.Close)

	// Pipeline bound to tenant "acme", maxConcurrent high so no backpressure.
	fsm := client.NewFSM()
	pipeline := client.NewMessagePipeline(tenant, fsm, 100, func() int { return 0 }, logger)

	// --- produce the three messages ---------------------------------------
	validMsg := client.TaskDispatchMessage{
		TaskID:        "task-" + suffix,
		TenantID:      tenant,
		SchemaVersion: "v1",
		WorkerType:    "http",
	}
	foreignMsg := client.TaskDispatchMessage{
		TaskID:        "foreign-" + suffix,
		TenantID:      "other-tenant",
		SchemaVersion: "v1",
		WorkerType:    "http",
	}
	badSchemaMsg := client.TaskDispatchMessage{
		TaskID:        "badschema-" + suffix,
		TenantID:      tenant,
		SchemaVersion: "v3",
		WorkerType:    "http",
	}
	produce(t, brokers, topic, validMsg, foreignMsg, badSchemaMsg)

	// --- consume + run through the pipeline -------------------------------
	// Collect dispositions for the three known taskIds. We poll until we have
	// observed all three or hit a deadline.
	deadline := time.Now().Add(30 * time.Second)
	seen := map[string]client.MessageDisposition{}
	wantIDs := map[string]bool{
		validMsg.TaskID:     true,
		foreignMsg.TaskID:   true,
		badSchemaMsg.TaskID: true,
	}

	for time.Now().Before(deadline) && len(seen) < len(wantIDs) {
		recs, ok := consumer.Poll()
		if !ok {
			t.Fatalf("consumer Poll returned not-ok unexpectedly")
		}
		for _, rec := range recs {
			msg, disp := pipeline.OnMessage(rec)
			if !wantIDs[msg.TaskID] {
				continue // ignore stray records from other runs
			}
			seen[msg.TaskID] = disp
			// Commit ONLY on acceptance; otherwise Withhold so the offset is
			// deferred (never crossed by a later commit) and the message would be
			// redelivered — mirroring the live consume loop's disposition handling.
			if disp == client.DispositionAccepted {
				consumer.Commit()
			} else {
				consumer.Withhold()
			}
		}
	}

	// --- assertions --------------------------------------------------------
	if got := seen[validMsg.TaskID]; got != client.DispositionAccepted {
		t.Errorf("valid v1 message: disposition = %q, want ACCEPTED", got)
	}
	if got := seen[foreignMsg.TaskID]; got != client.DispositionDroppedForeignTenant {
		t.Errorf("foreign-tenant message: disposition = %q, want DROPPED_FOREIGN_TENANT", got)
	}
	if got := seen[badSchemaMsg.TaskID]; got != client.DispositionRejectedSchema {
		t.Errorf("schemaVersion=v3 message: disposition = %q, want REJECTED_SCHEMA", got)
	}
	if len(seen) != len(wantIDs) {
		t.Errorf("only saw %d/%d expected messages: %v", len(seen), len(wantIDs), seen)
	}
}

// createTopic creates a single-partition topic via the controller broker.
func createTopic(t *testing.T, broker, topic string) {
	t.Helper()
	conn, err := kgo.Dial("tcp", broker)
	if err != nil {
		t.Fatalf("dial broker: %v", err)
	}
	defer conn.Close()

	controller, err := conn.Controller()
	if err != nil {
		t.Fatalf("get controller: %v", err)
	}
	ctrlConn, err := kgo.Dial("tcp", fmt.Sprintf("%s:%d", controller.Host, controller.Port))
	if err != nil {
		t.Fatalf("dial controller: %v", err)
	}
	defer ctrlConn.Close()

	if err := ctrlConn.CreateTopics(kgo.TopicConfig{
		Topic:             topic,
		NumPartitions:     1,
		ReplicationFactor: 1,
	}); err != nil {
		t.Fatalf("create topic %s: %v", topic, err)
	}

	// Wait until metadata reports the topic so the GroupReader can find it.
	wait := time.Now().Add(15 * time.Second)
	for time.Now().Before(wait) {
		parts, err := conn.ReadPartitions(topic)
		// 新主题元数据可能先于分区 leader 生效; 等待可路由后再写入, 避免竞态误报。
		if err == nil && len(parts) > 0 && parts[0].Leader.ID >= 0 && parts[0].Leader.Host != "" {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("topic %s did not appear in metadata", topic)
}

// deleteTopic removes the test topic (best-effort cleanup).
func deleteTopic(broker, topic string) {
	conn, err := kgo.Dial("tcp", broker)
	if err != nil {
		return
	}
	defer conn.Close()
	controller, err := conn.Controller()
	if err != nil {
		return
	}
	ctrlConn, err := kgo.Dial("tcp", fmt.Sprintf("%s:%d", controller.Host, controller.Port))
	if err != nil {
		return
	}
	defer ctrlConn.Close()
	_ = ctrlConn.DeleteTopics(topic)
}

// produce writes the given dispatch messages as JSON to the topic.
func produce(t *testing.T, brokers []string, topic string, msgs ...client.TaskDispatchMessage) {
	t.Helper()
	// 每个测试写入器拥有自己的元数据缓存, 避免前一主题的全局缓存隐藏新建主题。
	transport := &kgo.Transport{}
	defer transport.CloseIdleConnections()
	w := &kgo.Writer{
		Transport:    transport,
		Addr:         kgo.TCP(brokers...),
		Topic:        topic,
		Balancer:     &kgo.LeastBytes{},
		BatchTimeout: 100 * time.Millisecond,
	}
	defer w.Close()

	var records []kgo.Message
	for _, m := range msgs {
		b, err := json.Marshal(m)
		if err != nil {
			t.Fatalf("marshal msg: %v", err)
		}
		records = append(records, kgo.Message{Key: []byte(m.TaskID), Value: b})
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := w.WriteMessages(ctx, records...); err != nil {
		t.Fatalf("produce: %v", err)
	}
}

// 独立主题上的真实 Kafka 背压回归: 已拉取的第二条不得被后续提交跨过。
func TestWorkerBackpressureRetainsKafkaRecord(t *testing.T) {
	bootstrap := os.Getenv("BATCH_SDK_KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("BATCH_SDK_KAFKA_BOOTSTRAP not set")
	}
	brokers := strings.Split(bootstrap, ",")
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	workerCode := "backpressure-" + suffix
	topic := "batch.task.dispatch.http.node." + workerCode
	createTopic(t, brokers[0], topic)
	t.Cleanup(func() { deleteTopic(brokers[0], topic) })
	logger := log.New(io.Discard, "", 0)
	consumer, err := NewConsumer(Config{Brokers: brokers, TenantID: "acme", WorkerCode: workerCode, FetchMaxWait: 100 * time.Millisecond, Logger: logger})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(consumer.Close)
	platform := client.NewFakePlatform()
	releaseFirst := make(chan struct{})
	handler := client.HandlerFunc(func(ctx *client.TaskContext) client.TaskResult {
		if ctx.TaskID == "first" {
			<-releaseFirst
		}
		return client.Success(nil, "done")
	})
	worker := client.NewWorker(client.Config{TenantID: "acme", WorkerCode: workerCode,
		MaxConcurrentTasks: 1, HeartbeatInterval: time.Hour, LeaseRenewInterval: time.Hour,
		StopTimeout: time.Second}, platform, consumer, handler, nil, logger)
	t.Cleanup(func() { worker.Stop(time.Second) })
	t.Cleanup(func() {
		select {
		case <-releaseFirst:
		default:
			close(releaseFirst)
		}
	})
	produce(t, brokers, topic,
		client.TaskDispatchMessage{TaskID: "first", TenantID: "acme", SchemaVersion: "v2"},
		client.TaskDispatchMessage{TaskID: "deferred", TenantID: "acme", SchemaVersion: "v2"},
		client.TaskDispatchMessage{TaskID: "later", TenantID: "acme", SchemaVersion: "v2"})
	if err := worker.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(20 * time.Second)
	paused := false
	for time.Now().Before(deadline) {
		consumer.mu.Lock()
		paused = consumer.paused
		consumer.mu.Unlock()
		if paused {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !paused {
		t.Fatal("expected capacity pause while first task occupies the only slot")
	}
	close(releaseFirst)
	deadline = time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		_, deferred := platform.ReportFor("deferred")
		_, later := platform.ReportFor("later")
		if deferred && later {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("all fetched Kafka records must be claimed, executed and reported after resume")
}
