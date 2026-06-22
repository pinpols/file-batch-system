package kafka

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/client"
	kgo "github.com/segmentio/kafka-go"
)

// Integration test for the real Kafka adapter. ENV-GATED: a plain `go test ./...`
// without KAFKA_BOOTSTRAP skips it, so the default test run is broker-free and
// stays green. Run against the local PLAINTEXT broker with:
//
//	KAFKA_BOOTSTRAP=localhost:19092 go test ./...
//
// SASL/SCRAM-SHA-512 + TLS are config-supported (see Config / buildDialer) but
// the local broker is PLAINTEXT, so this test exercises the PLAINTEXT path; the
// SCRAM path is validated by construction (buildDialer) and code review.
func TestKafkaConsumer_Integration(t *testing.T) {
	bootstrap := os.Getenv("KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("KAFKA_BOOTSTRAP not set; skipping Kafka integration test (broker-free run)")
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
			// Commit ONLY on acceptance; otherwise drop pending so the offset
			// is withheld and the message would be redelivered.
			if disp == client.DispositionAccepted {
				consumer.Commit()
			} else {
				consumer.DropPending()
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
		if err == nil && len(parts) > 0 {
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
	w := &kgo.Writer{
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
