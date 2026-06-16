package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// 503 then 200 -> retry with backoff, eventual success.
func TestHTTPTransport_RetryThenSuccess(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt32(&calls, 1)
		if n == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	var slept []time.Duration
	tr := NewHTTPTransport(srv.URL, WithSleep(func(d time.Duration) { slept = append(slept, d) }))

	_, err := tr.Register(context.Background(), RegisterRequest{WorkerCode: "w1", TenantID: "t1"})
	if err != nil {
		t.Fatalf("expected success after retry, got %v", err)
	}
	if got := atomic.LoadInt32(&calls); got != 2 {
		t.Fatalf("expected 2 server hits (503 then 200), got %d", got)
	}
	if len(slept) != 1 || slept[0] != 200*time.Millisecond {
		t.Fatalf("expected one 200ms backoff sleep, got %v", slept)
	}
}

// 401 -> fatal, no retry.
func TestHTTPTransport_FailFastOn401(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}))
	_, err := tr.Claim(context.Background(), "task-1", "idem-1", ClaimRequest{TenantID: "t1", WorkerID: "w1"})
	if !IsFatal(err) {
		t.Fatalf("expected FatalError on 401, got %v", err)
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("expected exactly 1 hit (no retry on 401), got %d", got)
	}
}

// 409 -> idempotent success (claim already claimed).
func TestHTTPTransport_IdempotentOn409(t *testing.T) {
	gotIdemHeader := make(chan string, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotIdemHeader <- r.Header.Get("Idempotency-Key")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}))
	res, err := tr.Claim(context.Background(), "task-9", "idem-9", ClaimRequest{TenantID: "t9", WorkerID: "w9"})
	if err != nil {
		t.Fatalf("409 should be idempotent success, got err %v", err)
	}
	if !res.Idempotent {
		t.Fatalf("expected Idempotent=true on 409")
	}
	if h := <-gotIdemHeader; h != "idem-9" {
		t.Fatalf("expected Idempotency-Key header on claim, got %q", h)
	}
}

// Retry exhaustion: always 500 -> RetryExhaustedError after all attempts.
func TestHTTPTransport_RetryExhausted(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}), WithRetryTuning(10, 3))
	err := tr.Deactivate(context.Background(), "w1")
	if err == nil {
		t.Fatalf("expected error after exhausting retries")
	}
	// initial + 3 retries = 4 hits.
	if got := atomic.LoadInt32(&calls); got != 4 {
		t.Fatalf("expected 4 hits (1 initial + 3 retries), got %d", got)
	}
}
