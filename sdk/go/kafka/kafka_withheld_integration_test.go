package kafka

import (
	"context"
	"errors"
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

// 拒绝一个分区的消息时，其他分区仍应处理并提交，不能跨越被拒绝的位置。
func TestKafkaWithheldDoesNotBlockOtherPartition(t *testing.T) {
	bootstrap := os.Getenv("BATCH_SDK_KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("未设置隔离 Kafka 地址")
	}
	brokers := strings.Split(bootstrap, ",")
	worker := fmt.Sprintf("partition-isolation-%d", time.Now().UnixNano())
	topic := "batch.task.dispatch.http.node." + worker
	conn, err := kgo.Dial("tcp", brokers[0])
	if err != nil {
		t.Fatal(err)
	}
	controller, err := conn.Controller()
	_ = conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	ctrl, err := kgo.Dial("tcp", fmt.Sprintf("%s:%d", controller.Host, controller.Port))
	if err != nil {
		t.Fatal(err)
	}
	err = ctrl.CreateTopics(kgo.TopicConfig{Topic: topic, NumPartitions: 2, ReplicationFactor: 1})
	_ = ctrl.Close()
	if err != nil {
		t.Fatal(err)
	}
	write := func(partition int, value string) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		for {
			part, dialErr := kgo.DialLeader(ctx, "tcp", brokers[0], topic, partition)
			if dialErr == nil {
				_, dialErr = part.WriteMessages(kgo.Message{Value: []byte(value)})
				_ = part.Close()
			}
			if dialErr == nil {
				return
			}
			if ctx.Err() != nil {
				t.Fatal(dialErr)
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	write(0, "unsupported-schema")
	cfg := Config{Brokers: brokers, TenantID: "sdk-test", WorkerCode: worker, FetchMaxWait: 100 * time.Millisecond, Logger: log.New(io.Discard, "", 0)}
	c, err := NewConsumer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for {
		records, ok := c.Poll()
		if !ok {
			t.Fatal("消费者已停止")
		}
		if len(records) > 0 {
			c.Withhold()
			break
		}
	}
	write(1, "valid")
	done := make(chan bool, 1)
	go func() {
		for {
			records, ok := c.Poll()
			if !ok {
				done <- false
				return
			}
			if len(records) == 0 {
				continue
			}
			if string(records[0].Value) == "valid" {
				c.Commit()
				done <- true
				return
			}
			c.Withhold()
		}
	}()
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("正常分区未继续处理")
		}
	// 此用例验证持续暂扣时另一个分区最终提交，不将本机负载作为延迟验收标准。
	case <-time.After(30 * time.Second):
		c.Wakeup()
		<-done
		t.Fatal("拒绝消息阻塞了其他分区")
	}
	kc := &kgo.Client{Addr: kgo.TCP(brokers...)}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result, err := kc.OffsetFetch(ctx, &kgo.OffsetFetchRequest{GroupID: cfg.groupID(), Topics: map[string][]int{topic: {0, 1}}})
	if err != nil || result == nil || result.Error != nil {
		t.Fatalf("读取提交位置失败：%v", err)
	}
	seen := map[int]bool{}
	for _, partition := range result.Topics[topic] {
		if partition.Error != nil {
			t.Fatalf("读取分区 %d 的提交位置失败：%v", partition.Partition, partition.Error)
		}
		seen[partition.Partition] = true
		if partition.Partition == 0 && partition.CommittedOffset > 0 {
			t.Fatalf("被拒绝的分区被提交：%d", partition.CommittedOffset)
		}
		if partition.Partition == 1 && partition.CommittedOffset != 1 {
			t.Fatalf("正常分区未提交：%d", partition.CommittedOffset)
		}
	}
	if !seen[0] || !seen[1] {
		t.Fatalf("提交位置缺少分区：%v", seen)
	}
}

// 持续未确认时不得继续堆积后续 offset；问题解除后应重读并继续提交。
func TestKafkaWithheldBoundedRetry(t *testing.T) {
	bootstrap := os.Getenv("BATCH_SDK_KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("未设置隔离 Kafka 地址")
	}
	brokers := strings.Split(bootstrap, ",")
	worker := fmt.Sprintf("withheld-bounded-%d", time.Now().UnixNano())
	topic := "batch.task.dispatch.http.node." + worker
	createTopic(t, brokers[0], topic)
	cfg := Config{Brokers: brokers, TenantID: "sdk-test", WorkerCode: worker, FetchMaxWait: 100 * time.Millisecond, Logger: log.New(io.Discard, "", 0)}
	c, err := NewConsumer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	produce(t, brokers, topic,
		client.TaskDispatchMessage{TaskID: "first", TenantID: "sdk-test", SchemaVersion: "v1"},
		client.TaskDispatchMessage{TaskID: "second", TenantID: "sdk-test", SchemaVersion: "v1"},
	)
	poll := func() string {
		t.Helper()
		deadline := time.Now().Add(25 * time.Second)
		for time.Now().Before(deadline) {
			records, ok := c.Poll()
			if !ok {
				t.Fatal("消费者已停止")
			}
			if len(records) > 0 {
				return records[0].Key
			}
		}
		t.Fatal("等待记录超时")
		return ""
	}
	for i := 0; i < 3; i++ {
		if got := poll(); got != "first" {
			t.Fatalf("首次记录尚未确认，收到 %s", got)
		}
		c.Withhold()
		c.mu.Lock()
		retained := len(c.withheldOffsets[tpKey{topic: topic, partition: 0}])
		c.mu.Unlock()
		if retained > 1 {
			t.Fatalf("待确认 offset 数量=%d，预期不超过 1", retained)
		}
	}
	kc := &kgo.Client{Addr: kgo.TCP(brokers...)}
	readCommitted := func() int64 {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		result, err := kc.OffsetFetch(ctx, &kgo.OffsetFetchRequest{GroupID: cfg.groupID(), Topics: map[string][]int{topic: {0}}})
		if err != nil || result == nil || result.Error != nil {
			t.Fatalf("读取提交位置失败: result=%v err=%v", result, err)
		}
		return result.Topics[topic][0].CommittedOffset
	}
	if got := readCommitted(); got != -1 {
		t.Fatalf("未确认时 broker 提交位置=%d，预期 -1", got)
	}
	if got := poll(); got != "first" {
		t.Fatalf("解除阻塞前重读记录=%s", got)
	}
	c.Commit()
	if got := poll(); got != "second" {
		t.Fatalf("首条确认后记录=%s", got)
	}
	c.Commit()
	if got := readCommitted(); got != 2 {
		t.Fatalf("恢复后提交位置=%d，预期 2", got)
	}
}

// 独立多分区主题验证另一成员提交后，当前消费者重新取得分区可继续提交。
func TestKafkaWithheldTransferProgress(t *testing.T) {
	bootstrap := os.Getenv("BATCH_SDK_KAFKA_BOOTSTRAP")
	if bootstrap == "" {
		t.Skip("未设置隔离 Kafka 地址")
	}
	brokers := strings.Split(bootstrap, ",")
	worker := fmt.Sprintf("transfer-%d", time.Now().UnixNano())
	topic := "batch.task.dispatch.http.node." + worker
	conn, err := kgo.Dial("tcp", brokers[0])
	if err != nil {
		t.Fatal(err)
	}
	controller, err := conn.Controller()
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	ctrl, err := kgo.Dial("tcp", fmt.Sprintf("%s:%d", controller.Host, controller.Port))
	if err != nil {
		t.Fatal(err)
	}
	if err = ctrl.CreateTopics(kgo.TopicConfig{Topic: topic, NumPartitions: 6, ReplicationFactor: 1}); err != nil {
		t.Fatal(err)
	}
	_ = ctrl.Close()
	for partition := 0; partition < 6; partition++ {
		var part *kgo.Conn
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			part, err = kgo.DialLeader(context.Background(), "tcp", brokers[0], topic, partition)
			if err == nil {
				_, err = part.WriteMessages(kgo.Message{Value: []byte("first")})
				_ = part.Close()
				if err == nil {
					break
				}
			}
			if !errors.Is(err, kgo.NotLeaderForPartition) && !errors.Is(err, kgo.LeaderNotAvailable) {
				t.Fatal(err)
			}
			time.Sleep(100 * time.Millisecond)
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	cfg := Config{Brokers: brokers, TenantID: "sdk-test", WorkerCode: worker, FetchMaxWait: 100 * time.Millisecond, Logger: log.New(io.Discard, "", 0)}
	c, err := NewConsumer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for seen := 0; seen < 1; {
		records, ok := c.Poll()
		if !ok {
			t.Fatal("consumer stopped")
		}
		if len(records) == 0 {
			continue
		}
		c.Withhold()
		seen++
	}
	other := kgo.NewReader(kgo.ReaderConfig{Brokers: brokers, GroupID: cfg.groupID(), Topic: topic, MaxWait: 100 * time.Millisecond})
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	message, err := other.FetchMessage(ctx)
	if err != nil {
		cancel()
		_ = other.Close()
		t.Fatal(err)
	}
	if err = other.CommitMessages(ctx, message); err != nil {
		cancel()
		_ = other.Close()
		t.Fatal(err)
	}
	cancel()
	if err = other.Close(); err != nil {
		t.Fatal(err)
	}
	part, err := kgo.DialLeader(context.Background(), "tcp", brokers[0], topic, message.Partition)
	if err != nil {
		t.Fatal(err)
	}
	_, err = part.WriteMessages(kgo.Message{Value: []byte("next")})
	_ = part.Close()
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(25 * time.Second)
	found := false
	for time.Now().Before(deadline) {
		records, ok := c.Poll()
		if !ok {
			t.Fatal("consumer stopped")
		}
		if len(records) == 0 {
			continue
		}
		c.mu.Lock()
		m := c.pending[len(c.pending)-1]
		c.mu.Unlock()
		if m.Partition == message.Partition && m.Offset == 1 {
			c.Commit()
			found = true
			break
		}
		// 其他分区的记录可正常确认，避免测试自身持续阻塞整个消费者。
		c.Commit()
	}
	if !found {
		t.Fatal("原成员未重新获取已转交的分区")
	}
	kc := &kgo.Client{Addr: kgo.TCP(brokers...)}
	ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result, err := kc.OffsetFetch(ctx, &kgo.OffsetFetchRequest{GroupID: cfg.groupID(), Topics: map[string][]int{topic: {message.Partition}}})
	if err != nil {
		t.Fatal(err)
	}
	if result.Error != nil {
		t.Fatal(result.Error)
	}
	if got := result.Topics[topic][0].CommittedOffset; got != 2 {
		t.Fatalf("其他成员已提交 offset 0，当前提交 offset=%d，预期 2", got)
	}
}
