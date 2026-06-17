package client

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

const ErrorCodeIdempotentInFlight protocol.ErrorCode = "IDEMPOTENT_IN_FLIGHT"

// RetryPolicy controls the thin retry decorator. It retries handler-level
// TaskResult failures only; panics are left for the lifecycle guard.
type RetryPolicy struct {
	MaxAttempts       int
	InitialDelay      time.Duration
	BackoffMultiplier float64
	ShouldRetry       func(TaskResult) bool
}

func DefaultRetryPolicy() RetryPolicy {
	return RetryPolicy{
		MaxAttempts:       3,
		InitialDelay:      100 * time.Millisecond,
		BackoffMultiplier: 2,
		ShouldRetry: func(result TaskResult) bool {
			switch result.ErrorCode {
			case protocol.ErrorCodeExecutionFailed, protocol.ErrorCodeTimeout, protocol.ErrorCodeResourceExhausted:
				return true
			default:
				return false
			}
		},
	}
}

// WithRetry wraps a handler with a result-based retry policy.
func WithRetry(handler TaskHandler, policy RetryPolicy) TaskHandler {
	if handler == nil {
		panic("nil TaskHandler")
	}
	if policy.MaxAttempts < 1 {
		policy.MaxAttempts = 1
	}
	if policy.BackoffMultiplier <= 0 {
		policy.BackoffMultiplier = 1
	}
	if policy.ShouldRetry == nil {
		policy.ShouldRetry = DefaultRetryPolicy().ShouldRetry
	}
	return retryHandler{inner: handler, policy: policy}
}

type retryHandler struct {
	inner  TaskHandler
	policy RetryPolicy
}

func (h retryHandler) Execute(ctx *TaskContext) TaskResult {
	delay := h.policy.InitialDelay
	var result TaskResult
	for attempt := 1; attempt <= h.policy.MaxAttempts; attempt++ {
		result = h.inner.Execute(ctx)
		if result.IsSuccess() || attempt == h.policy.MaxAttempts || !h.policy.ShouldRetry(result) {
			return result
		}
		if delay > 0 {
			time.Sleep(delay)
			delay = time.Duration(float64(delay) * h.policy.BackoffMultiplier)
		}
	}
	return result
}

// IdempotencyEntity is the cached success snapshot returned by the idempotency
// decorator when the same key is replayed.
type IdempotencyEntity struct {
	Result TaskResult
}

// SdkIdempotencyStore is intentionally storage-agnostic. Production tenants can
// back it with DB/Redis; the SDK ships only noop and in-memory test stores.
type SdkIdempotencyStore interface {
	TryAcquire(ctx context.Context, key string, ttl time.Duration) (bool, error)
	Find(ctx context.Context, key string) (IdempotencyEntity, bool, error)
	Record(ctx context.Context, key string, entity IdempotencyEntity, ttl time.Duration) error
	Release(ctx context.Context, key string) error
}

type IdempotencyKeyFunc func(*TaskContext) (string, error)

func TaskIDIdempotencyKey(ctx *TaskContext) (string, error) {
	if ctx == nil || ctx.TaskID == "" {
		return "", fmt.Errorf("taskId is required")
	}
	return ctx.TaskID, nil
}

// WithIdempotency wraps a handler with acquire/cache/release semantics. Only
// successful results are recorded; failures release the in-flight marker.
func WithIdempotency(handler TaskHandler, store SdkIdempotencyStore, ttl time.Duration, keyFunc IdempotencyKeyFunc) TaskHandler {
	if handler == nil {
		panic("nil TaskHandler")
	}
	if store == nil {
		return handler
	}
	if keyFunc == nil {
		keyFunc = TaskIDIdempotencyKey
	}
	return idempotentHandler{inner: handler, store: store, ttl: ttl, keyFunc: keyFunc}
}

type idempotentHandler struct {
	inner   TaskHandler
	store   SdkIdempotencyStore
	ttl     time.Duration
	keyFunc IdempotencyKeyFunc
}

func (h idempotentHandler) Execute(taskCtx *TaskContext) (result TaskResult) {
	key, err := h.keyFunc(taskCtx)
	if err != nil || key == "" {
		if err == nil {
			err = fmt.Errorf("idempotency key is empty")
		}
		return Fail(protocol.ErrorCodeConfigInvalid, err.Error())
	}
	ctx := context.Background()
	if taskCtx != nil && taskCtx.Cancellation != nil {
		ctx = taskCtx.Cancellation.Context()
	}
	acquired, err := h.store.TryAcquire(ctx, key, h.ttl)
	if err != nil {
		return Fail(protocol.ErrorCodeExecutionFailed, "idempotency acquire failed: "+err.Error())
	}
	if !acquired {
		entity, found, err := h.store.Find(ctx, key)
		if err != nil {
			return Fail(protocol.ErrorCodeExecutionFailed, "idempotency lookup failed: "+err.Error())
		}
		if found {
			return entity.Result
		}
		return Fail(ErrorCodeIdempotentInFlight, "idempotent execution is already in flight")
	}

	defer func() {
		if r := recover(); r != nil {
			_ = h.store.Release(ctx, key)
			panic(r)
		}
	}()

	result = h.inner.Execute(taskCtx)
	if result.IsSuccess() {
		if err := h.store.Record(ctx, key, IdempotencyEntity{Result: result}, h.ttl); err != nil {
			return Fail(protocol.ErrorCodeExecutionFailed, "idempotency record failed: "+err.Error())
		}
		return result
	}
	_ = h.store.Release(ctx, key)
	return result
}

type NoopIdempotencyStore struct{}

func (NoopIdempotencyStore) TryAcquire(context.Context, string, time.Duration) (bool, error) {
	return true, nil
}

func (NoopIdempotencyStore) Find(context.Context, string) (IdempotencyEntity, bool, error) {
	return IdempotencyEntity{}, false, nil
}

func (NoopIdempotencyStore) Record(context.Context, string, IdempotencyEntity, time.Duration) error {
	return nil
}

func (NoopIdempotencyStore) Release(context.Context, string) error {
	return nil
}

type InMemoryIdempotencyStore struct {
	mu      sync.Mutex
	entries map[string]idempotencyEntry
	now     func() time.Time
}

type idempotencyEntry struct {
	entity  *IdempotencyEntity
	expires time.Time
}

func NewInMemoryIdempotencyStore() *InMemoryIdempotencyStore {
	return &InMemoryIdempotencyStore{
		entries: map[string]idempotencyEntry{},
		now:     time.Now,
	}
}

func (s *InMemoryIdempotencyStore) TryAcquire(_ context.Context, key string, ttl time.Duration) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteIfExpiredLocked(key)
	if _, exists := s.entries[key]; exists {
		return false, nil
	}
	s.entries[key] = idempotencyEntry{expires: expiresAt(s.now(), ttl)}
	return true, nil
}

func (s *InMemoryIdempotencyStore) Find(_ context.Context, key string) (IdempotencyEntity, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteIfExpiredLocked(key)
	entry, exists := s.entries[key]
	if !exists || entry.entity == nil {
		return IdempotencyEntity{}, false, nil
	}
	return *entry.entity, true, nil
}

func (s *InMemoryIdempotencyStore) Record(_ context.Context, key string, entity IdempotencyEntity, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[key] = idempotencyEntry{entity: &entity, expires: expiresAt(s.now(), ttl)}
	return nil
}

func (s *InMemoryIdempotencyStore) Release(_ context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.entries, key)
	return nil
}

func (s *InMemoryIdempotencyStore) deleteIfExpiredLocked(key string) {
	entry, exists := s.entries[key]
	if !exists || entry.expires.IsZero() || s.now().Before(entry.expires) {
		return
	}
	delete(s.entries, key)
}

func expiresAt(now time.Time, ttl time.Duration) time.Time {
	if ttl <= 0 {
		return time.Time{}
	}
	return now.Add(ttl)
}
