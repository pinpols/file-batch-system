package client

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pinpols/file-batch-system/sdk/go/protocol"
)

func TestWithRetryRetriesRetryableResult(t *testing.T) {
	attempts := 0
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		attempts++
		if attempts < 3 {
			return Fail(protocol.ErrorCodeExecutionFailed, "temporary")
		}
		return Success(map[string]any{"attempts": attempts}, "ok")
	})

	result := WithRetry(handler, RetryPolicy{
		MaxAttempts:       3,
		BackoffMultiplier: 1,
		ShouldRetry:       DefaultRetryPolicy().ShouldRetry,
	}).Execute(&TaskContext{TaskID: "t1"})

	if !result.IsSuccess() {
		t.Fatalf("expected success, got %#v", result)
	}
	if attempts != 3 {
		t.Fatalf("expected 3 attempts, got %d", attempts)
	}
}

func TestWithRetryStopsOnNonRetryableResult(t *testing.T) {
	attempts := 0
	handler := HandlerFunc(func(ctx *TaskContext) TaskResult {
		attempts++
		return Fail(protocol.ErrorCodeConfigInvalid, "bad config")
	})

	result := WithRetry(handler, DefaultRetryPolicy()).Execute(&TaskContext{TaskID: "t1"})

	if result.ErrorCode != protocol.ErrorCodeConfigInvalid {
		t.Fatalf("expected CONFIG_INVALID, got %s", result.ErrorCode)
	}
	if attempts != 1 {
		t.Fatalf("expected 1 attempt, got %d", attempts)
	}
}

func TestWithIdempotencyCachesSuccessfulResult(t *testing.T) {
	store := NewInMemoryIdempotencyStore()
	calls := 0
	handler := WithIdempotency(HandlerFunc(func(ctx *TaskContext) TaskResult {
		calls++
		return Success(map[string]any{"call": calls}, "done")
	}), store, time.Minute, nil)

	first := handler.Execute(&TaskContext{TaskID: "same"})
	second := handler.Execute(&TaskContext{TaskID: "same"})

	if !first.IsSuccess() || !second.IsSuccess() {
		t.Fatalf("expected success results: %#v %#v", first, second)
	}
	if calls != 1 {
		t.Fatalf("expected handler to run once, got %d", calls)
	}
	if second.Outputs["call"] != 1 {
		t.Fatalf("expected cached output from first call, got %#v", second.Outputs)
	}
}

func TestWithIdempotencyReleasesOnFailure(t *testing.T) {
	store := NewInMemoryIdempotencyStore()
	calls := 0
	handler := WithIdempotency(HandlerFunc(func(ctx *TaskContext) TaskResult {
		calls++
		if calls == 1 {
			return Fail(protocol.ErrorCodeExecutionFailed, "temporary")
		}
		return Success(nil, "ok")
	}), store, time.Minute, nil)

	if result := handler.Execute(&TaskContext{TaskID: "retry"}); result.IsSuccess() {
		t.Fatalf("expected first call to fail")
	}
	if result := handler.Execute(&TaskContext{TaskID: "retry"}); !result.IsSuccess() {
		t.Fatalf("expected second call to acquire after release, got %#v", result)
	}
	if calls != 2 {
		t.Fatalf("expected two handler calls, got %d", calls)
	}
}

func TestWithIdempotencyInFlightWithoutSnapshot(t *testing.T) {
	store := NewInMemoryIdempotencyStore()
	acquired, err := store.TryAcquire(context.Background(), "busy", time.Minute)
	if err != nil || !acquired {
		t.Fatalf("pre-acquire failed: acquired=%v err=%v", acquired, err)
	}

	result := WithIdempotency(HandlerFunc(func(ctx *TaskContext) TaskResult {
		t.Fatalf("handler should not run")
		return Success(nil, "")
	}), store, time.Minute, func(*TaskContext) (string, error) {
		return "busy", nil
	}).Execute(&TaskContext{TaskID: "t1"})

	if result.ErrorCode != ErrorCodeIdempotentInFlight {
		t.Fatalf("expected IDEMPOTENT_IN_FLIGHT, got %s", result.ErrorCode)
	}
}

type failingRecordStore struct {
	*InMemoryIdempotencyStore
}

func (s failingRecordStore) Record(context.Context, string, IdempotencyEntity, time.Duration) error {
	return errors.New("store down")
}

func (s failingRecordStore) RecordOwned(
	context.Context,
	string,
	IdempotencyEntity,
	IdempotencyClaim,
	time.Duration,
) (bool, error) {
	return false, errors.New("store down")
}

func TestWithIdempotencyRecordFailureReportsFailure(t *testing.T) {
	store := failingRecordStore{InMemoryIdempotencyStore: NewInMemoryIdempotencyStore()}
	result := WithIdempotency(HandlerFunc(func(ctx *TaskContext) TaskResult {
		return Success(nil, "ok")
	}), store, time.Minute, nil).Execute(&TaskContext{TaskID: "t1"})

	if result.ErrorCode != protocol.ErrorCodeExecutionFailed {
		t.Fatalf("expected EXECUTION_FAILED, got %s", result.ErrorCode)
	}
}

func TestStaleClaimCannotOverwriteNewerSuccess(t *testing.T) {
	var nowNanos atomic.Int64
	nowNanos.Store(time.Unix(1_000, 0).UnixNano())
	store := NewInMemoryIdempotencyStore()
	store.now = func() time.Time { return time.Unix(0, nowNanos.Load()) }
	ctx := context.Background()

	claimA, acquired, err := store.Acquire(ctx, "shared", 10*time.Second)
	if err != nil || !acquired {
		t.Fatalf("A acquire failed: acquired=%v err=%v", acquired, err)
	}
	nowNanos.Store(time.Unix(1_011, 0).UnixNano())
	claimB, acquired, err := store.Acquire(ctx, "shared", 10*time.Second)
	if err != nil || !acquired {
		t.Fatalf("B acquire failed: acquired=%v err=%v", acquired, err)
	}
	recordedB, err := store.RecordOwned(
		ctx,
		"shared",
		IdempotencyEntity{Result: Success(map[string]any{"winner": "B"}, "B")},
		claimB,
		time.Minute,
	)
	if err != nil || !recordedB {
		t.Fatalf("B record failed: recorded=%v err=%v", recordedB, err)
	}

	if released, _ := store.ReleaseOwned(ctx, "shared", claimA); released {
		t.Fatalf("stale claim must not release the new owner")
	}
	recordedA, _ := store.RecordOwned(
		ctx,
		"shared",
		IdempotencyEntity{Result: Success(map[string]any{"winner": "A"}, "A")},
		claimA,
		time.Minute,
	)
	if recordedA {
		t.Fatalf("stale claim must not overwrite the new result")
	}
	entity, found, err := store.Find(ctx, "shared")
	if err != nil || !found {
		t.Fatalf("expected B result, found=%v err=%v", found, err)
	}
	if entity.Result.Outputs["winner"] != "B" {
		t.Fatalf("expected B result, got %#v", entity.Result.Outputs)
	}
}
