package client

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestControlPlaneDialer_UsesBoundedHappyEyeballsFallback(t *testing.T) {
	dialer := newControlPlaneDialer()
	if dialer.FallbackDelay != 250*time.Millisecond {
		t.Fatalf("expected 250ms fallback delay, got %s", dialer.FallbackDelay)
	}
	if dialer.Timeout != defaultHTTPTimeout {
		t.Fatalf("expected connect timeout %s, got %s", defaultHTTPTimeout, dialer.Timeout)
	}
}

func TestHTTPTransport_RealSocketHappyEyeballsMatrix(t *testing.T) {
	matrixFile := os.Getenv("BATCH_SDK_HE_MATRIX_FILE")
	if matrixFile == "" {
		t.Skip("BATCH_SDK_HE_MATRIX_FILE not set")
	}
	data, err := os.ReadFile(matrixFile)
	if err != nil {
		t.Fatalf("read HE matrix: %v", err)
	}
	var matrix struct {
		Scenarios map[string]struct {
			Addresses []string `json:"addresses"`
			BaseURL   string   `json:"base_url"`
			Expected  string   `json:"expected"`
			TimeoutMS int      `json:"timeout_ms"`
		} `json:"scenarios"`
	}
	if err := json.Unmarshal(data, &matrix); err != nil {
		t.Fatalf("parse HE matrix: %v", err)
	}

	for scenarioName, scenario := range matrix.Scenarios {
		t.Run(scenarioName, func(t *testing.T) {
			resolver := newOrderedLoopbackResolver(t, scenario.Addresses)
			dialer := newControlPlaneDialer()
			dialer.Timeout = time.Duration(scenario.TimeoutMS) * time.Millisecond
			dialer.Resolver = resolver
			httpTransport := &http.Transport{DialContext: dialer.DialContext}
			httpClient := &http.Client{
				Timeout:   time.Duration(scenario.TimeoutMS) * time.Millisecond,
				Transport: httpTransport,
			}
			transport := NewHTTPTransport(
				scenario.BaseURL,
				WithTenantID("tx"),
				WithHTTPClient(httpClient),
			)

			started := time.Now()
			_, callErr := transport.Heartbeat(
				context.Background(),
				"w-1",
				HeartbeatRequest{TenantID: "tx"},
			)
			elapsed := time.Since(started)
			httpTransport.CloseIdleConnections()
			if scenario.Expected == "success" && callErr != nil {
				t.Fatalf("expected success, got %v", callErr)
			}
			if scenario.Expected == "timeout" && callErr == nil {
				t.Fatal("expected timeout, got success")
			}
			if elapsed >= 2500*time.Millisecond {
				t.Fatalf("exceeded bound: %s", elapsed)
			}
			if scenarioName == "ipv6_blackhole" && elapsed < 150*time.Millisecond {
				t.Fatalf("did not exercise fallback delay: %s", elapsed)
			}
		})
	}
}

func newOrderedLoopbackResolver(t *testing.T, addresses []string) *net.Resolver {
	t.Helper()
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("start DNS fixture: %v", err)
	}
	t.Cleanup(func() { _ = server.Close() })
	preference := make(map[uint16]int, len(addresses))
	for index, address := range addresses {
		if strings.Contains(address, ":") {
			preference[28] = index
		} else {
			preference[1] = index
		}
	}
	go func() {
		buffer := make([]byte, 512)
		for {
			n, peer, readErr := server.ReadFrom(buffer)
			if readErr != nil {
				return
			}
			query := append([]byte(nil), buffer[:n]...)
			go func() {
				response, queryType, responseErr := loopbackDNSResponse(query)
				if responseErr != nil {
					return
				}
				if preference[queryType] > 0 {
					time.Sleep(20 * time.Millisecond)
				}
				_, _ = server.WriteTo(response, peer)
			}()
		}
	}()
	serverAddress := server.LocalAddr().String()
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			var dialer net.Dialer
			return dialer.DialContext(ctx, "udp4", serverAddress)
		},
	}
}

func loopbackDNSResponse(query []byte) ([]byte, uint16, error) {
	if len(query) < 17 {
		return nil, 0, fmt.Errorf("short DNS query")
	}
	questionEnd := 12
	for {
		if questionEnd >= len(query) {
			return nil, 0, fmt.Errorf("invalid DNS name")
		}
		labelLength := int(query[questionEnd])
		questionEnd++
		if labelLength == 0 {
			break
		}
		questionEnd += labelLength
	}
	if questionEnd+4 > len(query) {
		return nil, 0, fmt.Errorf("missing DNS question type")
	}
	queryType := binary.BigEndian.Uint16(query[questionEnd : questionEnd+2])
	questionEnd += 4
	var record []byte
	switch queryType {
	case 1:
		record = net.ParseIP("127.0.0.1").To4()
	case 28:
		record = net.ParseIP("::1").To16()
	default:
		return nil, queryType, fmt.Errorf("unsupported DNS query type %d", queryType)
	}
	response := append([]byte(nil), query[:questionEnd]...)
	response[2] = 0x81
	response[3] = 0x80
	binary.BigEndian.PutUint16(response[6:8], 1)
	answer := make([]byte, 12+len(record))
	answer[0], answer[1] = 0xc0, 0x0c
	binary.BigEndian.PutUint16(answer[2:4], queryType)
	binary.BigEndian.PutUint16(answer[4:6], 1)
	binary.BigEndian.PutUint32(answer[6:10], 1)
	binary.BigEndian.PutUint16(answer[10:12], uint16(len(record)))
	copy(answer[12:], record)
	return append(response, answer...), queryType, nil
}

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

func TestHTTPTransport_SendsTenantAndAPIKeyHeaders(t *testing.T) {
	gotHeaders := make(chan http.Header, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHeaders <- r.Header.Clone()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithTenantID("tenant-a"), WithAPIKey("secret-key"))
	_, err := tr.Register(context.Background(), RegisterRequest{WorkerCode: "w1", TenantID: "tenant-a"})
	if err != nil {
		t.Fatalf("register failed: %v", err)
	}
	headers := <-gotHeaders
	if got := headers.Get("X-Batch-Tenant-Id"); got != "tenant-a" {
		t.Fatalf("expected tenant header tenant-a, got %q", got)
	}
	if got := headers.Get("X-Batch-Api-Key"); got != "secret-key" {
		t.Fatalf("expected API key header secret-key, got %q", got)
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

func TestHTTPTransport_ClaimDecodesPartitionInvocationID(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"partitionInvocationId":"inv-from-claim"}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithHTTPClient(srv.Client()))
	result, err := tr.Claim(
		context.Background(),
		"task-claim-invocation",
		"idem-claim-invocation",
		ClaimRequest{TenantID: "t1", WorkerID: "w1"},
	)
	if err != nil {
		t.Fatalf("Claim: %v", err)
	}
	if result.PartitionInvocationID != "inv-from-claim" {
		t.Fatalf("claim partitionInvocationId = %q", result.PartitionInvocationID)
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
	w.report(msg, "inv-7", Success(nil, "done"))

	if gotInv != "inv-7" {
		t.Fatalf("report must send partitionInvocationId=inv-7, got %q", gotInv)
	}
	if gotKey == "" || gotKey == "idem-task-1" || !strings.HasPrefix(gotKey, "go-") {
		t.Fatalf("report must mint a fresh go-<uuid> Idempotency-Key, got %q", gotKey)
	}
}
