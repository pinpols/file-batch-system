package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
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

// §C single-attempt exemption (fixture 25): heartbeat & renew are periodic
// ticks — a single failure waits for the next tick, NO internal backoff. A 503
// must produce exactly ONE hit (unlike claim/report/deactivate which retry).
func TestHTTPTransport_HeartbeatRenewSingleAttempt(t *testing.T) {
	for _, tc := range []struct {
		name string
		call func(tr *HTTPTransport) error
	}{
		{"heartbeat", func(tr *HTTPTransport) error {
			_, err := tr.Heartbeat(context.Background(), "w1", HeartbeatRequest{})
			return err
		}},
		{"renew", func(tr *HTTPTransport) error {
			_, err := tr.Renew(context.Background(), "task-1", RenewRequest{})
			return err
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls int32
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				atomic.AddInt32(&calls, 1)
				w.WriteHeader(http.StatusServiceUnavailable)
			}))
			defer srv.Close()
			tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}), WithRetryTuning(10, 3))
			if err := tc.call(tr); err == nil {
				t.Fatalf("%s: expected error on 503", tc.name)
			}
			if got := atomic.LoadInt32(&calls); got != 1 {
				t.Fatalf("%s: §C exemption requires a single attempt, got %d hits", tc.name, got)
			}
		})
	}
}

// fixture 14: renew must carry the claim-time partitionInvocationId on the wire,
// else the platform's partition CAS renew fails → 409 → double-run.
func TestRenewOnce_ThreadsPartitionInvocationId(t *testing.T) {
	var gotInv string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if v, ok := body["partitionInvocationId"].(string); ok {
			gotInv = v
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"leaseUntil":"2030-01-01T00:00:00Z"}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}))
	reg := NewInFlightRegistry()
	reg.Add("task-1", NewCancellationSignal(context.Background()), "inv-42")
	s := NewLeaseRenewalScheduler(tr, reg, "w1", "t1", WithLeaseLogger(quietLogger()))
	s.RenewOnce(context.Background())

	if gotInv != "inv-42" {
		t.Fatalf("renew must send partitionInvocationId=inv-42, got %q", gotInv)
	}
}

// fixture 24 + ADR-014: report carries a FRESH idempotency key AND the
// partitionInvocationId on the wire.
func TestReport_FreshKeyAndPartitionInvocationId(t *testing.T) {
	var gotKey, gotInv string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotKey = r.Header.Get("Idempotency-Key")
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		if v, ok := body["partitionInvocationId"].(string); ok {
			gotInv = v
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}))
	w := &Worker{cfg: Config{TenantID: "t1", WorkerCode: "w1"}, transport: tr, logger: quietLogger()}
	msg := TaskDispatchMessage{TaskID: "task-1", TenantID: "t1", RuntimeAttributes: map[string]any{"partitionInvocationId": "inv-7"}}
	w.report(msg, Success(nil, "done"))

	if gotInv != "inv-7" {
		t.Fatalf("report must send partitionInvocationId=inv-7, got %q", gotInv)
	}
	if gotKey == "" || gotKey == "idem-task-1" || !strings.HasPrefix(gotKey, "go-") {
		t.Fatalf("report must mint a fresh go-<uuid> Idempotency-Key, got %q", gotKey)
	}
}
