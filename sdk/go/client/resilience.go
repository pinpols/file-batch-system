package client

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/pinpols/file-batch-system/sdk/go/protocol"
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
			scaledDelayNanoseconds := float64(delay) * h.policy.BackoffMultiplier
			delay = time.Duration(scaledDelayNanoseconds)
		}
	}
	return result
}

// IdempotencyEntity is the cached success snapshot returned by the idempotency
// decorator when the same key is replayed.
type IdempotencyEntity struct {
	Result TaskResult
}

// IdempotencyClaim 是取得执行权时返回的持有者凭据。实现 SdkIdempotencyStoreV2
// 的存储用它拒绝租约已过期执行者的迟到写入。
type IdempotencyClaim struct {
	Token string
}

const legacyIdempotencyToken = "legacy"

// SdkIdempotencyStore is intentionally storage-agnostic. Production tenants can
// back it with DB/Redis; the SDK ships only noop and in-memory test stores.
type SdkIdempotencyStore interface {
	TryAcquire(ctx context.Context, key string, ttl time.Duration) (bool, error)
	Find(ctx context.Context, key string) (IdempotencyEntity, bool, error)
	Record(ctx context.Context, key string, entity IdempotencyEntity, ttl time.Duration) error
	Release(ctx context.Context, key string) error
}

// SdkIdempotencyStoreV2 是 SdkIdempotencyStore 的持有者凭据扩展。既有自定义存储
// 继续走旧四方法；实现本接口的存储获得原子所有权校验。
type SdkIdempotencyStoreV2 interface {
	Acquire(ctx context.Context, key string, ttl time.Duration) (IdempotencyClaim, bool, error)
	RecordOwned(
		ctx context.Context,
		key string,
		entity IdempotencyEntity,
		claim IdempotencyClaim,
		ttl time.Duration,
	) (bool, error)
	ReleaseOwned(ctx context.Context, key string, claim IdempotencyClaim) (bool, error)
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
		failureMessage := err.Error()
		return Fail(protocol.ErrorCodeConfigInvalid, failureMessage)
	}
	ctx := context.Background()
	if taskCtx != nil && taskCtx.Cancellation != nil {
		ctx = taskCtx.Cancellation.Context()
	}
	claim, acquired, err := acquireIdempotencyClaim(h.store, ctx, key, h.ttl)
	if err != nil {
		failureSummary := "idempotency acquire failed: " + err.Error()
		return Fail(protocol.ErrorCodeExecutionFailed, failureSummary)
	}
	if !acquired {
		entity, found, err := h.store.Find(ctx, key)
		if err != nil {
			failureSummary := "idempotency lookup failed: " + err.Error()
			return Fail(protocol.ErrorCodeExecutionFailed, failureSummary)
		}
		if found {
			return entity.Result
		}
		return Fail(ErrorCodeIdempotentInFlight, "idempotent execution is already in flight")
	}

	defer func() {
		if r := recover(); r != nil {
			releaseIdempotencyClaim(h.store, ctx, key, claim)
			panic(r)
		}
	}()

	result = h.inner.Execute(taskCtx)
	if result.IsSuccess() {
		recorded, err := recordIdempotencyClaim(
			h.store, ctx, key, IdempotencyEntity{Result: result}, claim, h.ttl)
		if err != nil {
			failureSummary := "idempotency record failed: " + err.Error()
			return Fail(protocol.ErrorCodeExecutionFailed, failureSummary)
		}
		// recorded=false means this executor lost ownership; keep the newer result.
		_ = recorded
		return result
	}
	releaseIdempotencyClaim(h.store, ctx, key, claim)
	return result
}

func acquireIdempotencyClaim(
	store SdkIdempotencyStore,
	ctx context.Context,
	key string,
	ttl time.Duration,
) (IdempotencyClaim, bool, error) {
	if v2, ok := store.(SdkIdempotencyStoreV2); ok {
		return v2.Acquire(ctx, key, ttl)
	}
	acquired, err := store.TryAcquire(ctx, key, ttl)
	if err != nil {
		return IdempotencyClaim{}, false, err
	}
	if !acquired {
		return IdempotencyClaim{}, false, nil
	}
	return IdempotencyClaim{Token: legacyIdempotencyToken}, true, nil
}

func recordIdempotencyClaim(
	store SdkIdempotencyStore,
	ctx context.Context,
	key string,
	entity IdempotencyEntity,
	claim IdempotencyClaim,
	ttl time.Duration,
) (bool, error) {
	if v2, ok := store.(SdkIdempotencyStoreV2); ok {
		return v2.RecordOwned(ctx, key, entity, claim, ttl)
	}
	if err := store.Record(ctx, key, entity, ttl); err != nil {
		return false, err
	}
	return true, nil
}

func releaseIdempotencyClaim(
	store SdkIdempotencyStore,
	ctx context.Context,
	key string,
	claim IdempotencyClaim,
) {
	if v2, ok := store.(SdkIdempotencyStoreV2); ok {
		_, _ = v2.ReleaseOwned(ctx, key, claim)
		return
	}
	_ = store.Release(ctx, key)
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
	mu       sync.Mutex
	entries  map[string]idempotencyEntry
	now      func() time.Time
	sequence uint64
}

type idempotencyEntry struct {
	entity  *IdempotencyEntity
	expires time.Time
	token   string
}

func NewInMemoryIdempotencyStore() *InMemoryIdempotencyStore {
	return &InMemoryIdempotencyStore{
		entries: map[string]idempotencyEntry{},
		now:     time.Now,
	}
}

func (s *InMemoryIdempotencyStore) TryAcquire(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	_, acquired, err := s.Acquire(ctx, key, ttl)
	return acquired, err
}

func (s *InMemoryIdempotencyStore) Acquire(
	_ context.Context,
	key string,
	ttl time.Duration,
) (IdempotencyClaim, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteIfExpiredLocked(key)
	if _, exists := s.entries[key]; exists {
		return IdempotencyClaim{}, false, nil
	}
	currentTime := s.now()
	s.sequence++
	token := fmt.Sprintf("%d-%d", currentTime.UnixNano(), s.sequence)
	s.entries[key] = idempotencyEntry{expires: expiresAt(currentTime, ttl), token: token}
	return IdempotencyClaim{Token: token}, true, nil
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
	currentTime := s.now()
	expirationTime := expiresAt(currentTime, ttl)
	existing := s.entries[key]
	s.entries[key] = idempotencyEntry{entity: &entity, expires: expirationTime, token: existing.token}
	return nil
}

func (s *InMemoryIdempotencyStore) RecordOwned(
	_ context.Context,
	key string,
	entity IdempotencyEntity,
	claim IdempotencyClaim,
	ttl time.Duration,
) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteIfExpiredLocked(key)
	entry, exists := s.entries[key]
	if !exists || entry.token != claim.Token {
		return false, nil
	}
	currentTime := s.now()
	s.entries[key] = idempotencyEntry{
		entity:  &entity,
		expires: expiresAt(currentTime, ttl),
		token:   claim.Token,
	}
	return true, nil
}

func (s *InMemoryIdempotencyStore) ReleaseOwned(
	_ context.Context,
	key string,
	claim IdempotencyClaim,
) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteIfExpiredLocked(key)
	entry, exists := s.entries[key]
	if !exists || entry.token != claim.Token {
		return false, nil
	}
	delete(s.entries, key)
	return true, nil
}

func (s *InMemoryIdempotencyStore) Release(_ context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.entries, key)
	return nil
}

func (s *InMemoryIdempotencyStore) deleteIfExpiredLocked(key string) {
	entry, exists := s.entries[key]
	if !exists || entry.expires.IsZero() {
		return
	}
	if s.now().Before(entry.expires) {
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
