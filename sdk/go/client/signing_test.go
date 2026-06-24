package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Golden vectors — must stay byte-identical with the server (batch-common
// RequestSignatures), the Java SDK (RequestSigner), and every other language
// SDK. Any drift here is a hard contract break.
const (
	goldenAPIKey    = "golden-key"
	goldenMethod    = "POST"
	goldenPath      = "/internal/tasks/42/report"
	goldenTimestamp = "1700000000000"
	goldenNonce     = "golden-nonce"
	goldenBody      = `{"tenantId":"t1","success":true}`

	goldenBodySha256  = "c9a04b2061b2c381193ee868b9d89bc16979c738d257f8495d18457a83462dd5"
	goldenSignature   = "287108832407aec1bc689c97ac22037b7114b2702671dfb20d1aacc6edeb0898"
	goldenEmptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
)

func TestSign_GoldenBodySha256(t *testing.T) {
	got := bodySha256Hex([]byte(goldenBody))
	if got != goldenBodySha256 {
		t.Fatalf("bodySha256Hex mismatch:\n got=%s\nwant=%s", got, goldenBodySha256)
	}
}

func TestSign_GoldenEmptyBodySha256(t *testing.T) {
	// nil and empty-slice bodies must both hash the empty input.
	if got := bodySha256Hex(nil); got != goldenEmptySha256 {
		t.Fatalf("bodySha256Hex(nil) mismatch:\n got=%s\nwant=%s", got, goldenEmptySha256)
	}
	if got := bodySha256Hex([]byte{}); got != goldenEmptySha256 {
		t.Fatalf("bodySha256Hex([]) mismatch:\n got=%s\nwant=%s", got, goldenEmptySha256)
	}
}

func TestSign_GoldenSignature(t *testing.T) {
	got := signRequest(goldenAPIKey, goldenMethod, goldenPath, goldenTimestamp, goldenNonce, []byte(goldenBody))
	if got != goldenSignature {
		t.Fatalf("signRequest mismatch:\n got=%s\nwant=%s", got, goldenSignature)
	}
}

// Method must be uppercased in the canonical string: a lowercase method yields
// the same signature as the uppercase golden case.
func TestSign_MethodUppercased(t *testing.T) {
	got := signRequest(goldenAPIKey, "post", goldenPath, goldenTimestamp, goldenNonce, []byte(goldenBody))
	if got != goldenSignature {
		t.Fatalf("lowercase method should match uppercase golden:\n got=%s\nwant=%s", got, goldenSignature)
	}
}

// End-to-end: when signing is enabled AND an api key is set, a write request
// carries all three signature headers plus X-Batch-Api-Key, and the signature
// recomputes from the captured timestamp/nonce/body.
func TestSign_TransportAttachesHeadersOnWrite(t *testing.T) {
	var hdr http.Header
	var rawBody []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hdr = r.Header.Clone()
		buf := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(buf)
		rawBody = buf
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL,
		WithSleep(func(time.Duration) {}),
		WithAPIKey(goldenAPIKey),
		WithRequestSigning(true),
	)

	err := tr.Report(context.Background(), "42", "idem-1",
		ReportRequest{TaskID: "42", TenantID: "t1", WorkerID: "w1", Success: true})
	if err != nil {
		t.Fatalf("report: %v", err)
	}

	if got := hdr.Get("X-Batch-Api-Key"); got != goldenAPIKey {
		t.Fatalf("X-Batch-Api-Key = %q, want %q", got, goldenAPIKey)
	}
	ts := hdr.Get(HeaderSignatureTimestamp)
	nonce := hdr.Get(HeaderSignatureNonce)
	sig := hdr.Get(HeaderSignature)
	if ts == "" || nonce == "" || sig == "" {
		t.Fatalf("missing signature headers: ts=%q nonce=%q sig=%q", ts, nonce, sig)
	}
	want := signRequest(goldenAPIKey, http.MethodPost, "/internal/tasks/42/report", ts, nonce, rawBody)
	if sig != want {
		t.Fatalf("signature header mismatch:\n got=%s\nwant=%s", sig, want)
	}
}

// Signing is opt-in: with the flag off (default), no signature headers are sent,
// even though the api key header still is.
func TestSign_DisabledByDefault(t *testing.T) {
	var hdr http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hdr = r.Header.Clone()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}), WithAPIKey(goldenAPIKey))
	if err := tr.Report(context.Background(), "42", "idem-1",
		ReportRequest{TaskID: "42", TenantID: "t1", WorkerID: "w1", Success: true}); err != nil {
		t.Fatalf("report: %v", err)
	}
	if got := hdr.Get("X-Batch-Api-Key"); got != goldenAPIKey {
		t.Fatalf("X-Batch-Api-Key = %q, want %q", got, goldenAPIKey)
	}
	if sig := hdr.Get(HeaderSignature); sig != "" {
		t.Fatalf("expected no signature when disabled, got %q", sig)
	}
}

// No api key => no signing even when enabled (mirrors Java: empty key never signs).
func TestSign_NoApiKeyNoSignature(t *testing.T) {
	var hdr http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hdr = r.Header.Clone()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	tr := NewHTTPTransport(srv.URL, WithSleep(func(time.Duration) {}), WithRequestSigning(true))
	if err := tr.Report(context.Background(), "42", "idem-1",
		ReportRequest{TaskID: "42", TenantID: "t1", WorkerID: "w1", Success: true}); err != nil {
		t.Fatalf("report: %v", err)
	}
	if got := hdr.Get("X-Batch-Api-Key"); got != "" {
		t.Fatalf("expected no api key header, got %q", got)
	}
	if sig := hdr.Get(HeaderSignature); sig != "" {
		t.Fatalf("expected no signature without api key, got %q", sig)
	}
}
