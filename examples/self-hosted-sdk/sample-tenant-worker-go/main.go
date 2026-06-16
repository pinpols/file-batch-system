// Command sample-tenant-worker-go is a minimal, runnable self-hosted worker
// built on the Go BYO SDK (github.com/pinpols/file-batch-system/batch-worker-sdk-go).
//
// It demonstrates the smallest end-to-end wiring a tenant needs:
//   - read config + credentials from the environment (never from payloads),
//   - build the HTTP control-plane transport (API key via an Authorization header),
//   - build the real Kafka consumer adapter (PLAINTEXT or SASL/SCRAM-SHA-512),
//   - register an echo-style TaskHandler,
//   - run until SIGINT/SIGTERM with a graceful drain.
//
// Copy this directory into your own repo and swap echoHandler for your business
// logic. It needs a live platform + broker to actually connect; the build is
// verifiable offline against the local SDK via the go.mod replace directives.
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/client"
	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/kafka"
)

func main() {
	logger := log.New(os.Stdout, "[sample-worker] ", log.LstdFlags|log.LUTC)

	cfg, err := loadConfig()
	if err != nil {
		logger.Fatalf("FATAL config error: %v", err)
	}

	// (2) HTTP control-plane transport. The SDK has no built-in API-key option,
	// so inject the key via a RoundTripper that stamps the Authorization header
	// on every control-plane request. Keep the SDK's 10s client timeout (the §4
	// Go pit) by wrapping http.DefaultTransport rather than replacing the client.
	transport := client.NewHTTPTransport(
		cfg.BaseURL,
		client.WithHTTPClient(newAuthedHTTPClient(cfg.APIKey)),
	)

	// (3) Real Kafka consumer adapter (nested module). PLAINTEXT locally; when
	// both SASL vars are set it negotiates SASL/SCRAM-SHA-512.
	consumer, err := kafka.NewConsumer(kafka.Config{
		Brokers:      cfg.KafkaBootstrap,
		TenantID:     cfg.TenantID,
		WorkerCode:   cfg.WorkerCode,
		SASLUsername: cfg.SASLUsername,
		SASLPassword: cfg.SASLPassword,
		Logger:       logger,
	})
	if err != nil {
		logger.Fatalf("FATAL kafka consumer: %v", err)
	}

	// (4)+(5) Wire the worker: static config + transport + consumer + handler +
	// sensitive-data validator (§1.8) + logger.
	worker := client.NewWorker(
		client.Config{
			WorkerCode:     cfg.WorkerCode,
			TenantID:       cfg.TenantID,
			BuildID:        "sample-tenant-worker-go@dev",
			SDKVersion:     "go-byo-sdk",
			CapabilityTags: []string{"echo"},
		},
		transport,
		consumer,
		&echoHandler{logger: logger},
		client.NewSensitiveValidator(),
		logger,
	)

	logger.Printf("INFO starting worker code=%s tenant=%s base=%s brokers=%v",
		cfg.WorkerCode, cfg.TenantID, cfg.BaseURL, cfg.KafkaBootstrap)

	// (6) Block until SIGINT/SIGTERM, then graceful Stop(30s).
	if err := worker.RunUntilSignal(context.Background()); err != nil {
		logger.Fatalf("FATAL worker exited: %v", err)
	}
	logger.Printf("INFO worker shut down cleanly code=%s", cfg.WorkerCode)
}

// echoHandler is the example business SPI: it logs the task and echoes the
// effective config back as Outputs, mirroring the Java/Python `echo` samples.
type echoHandler struct{ logger *log.Logger }

func (h *echoHandler) Execute(tc *client.TaskContext) client.TaskResult {
	h.logger.Printf("INFO echo handler taskId=%s traceId=%s params=%v",
		tc.TaskID, tc.TraceID, tc.EffectiveConfig)

	// Cooperative cancellation: bail early if the lease was cancelled.
	if tc.Cancellation.IsCancellationRequested() {
		return client.Fail("CANCELLED", "task cancelled before echo")
	}

	outputs := map[string]any{
		"echoedParams": tc.EffectiveConfig,
		"handledBy":    "sample-tenant-worker-go",
	}
	return client.Success(outputs, fmt.Sprintf("echoed %d param(s)", len(tc.EffectiveConfig)))
}

// ---------------------------------------------------------------------------
// config
// ---------------------------------------------------------------------------

type config struct {
	BaseURL        string
	APIKey         string
	TenantID       string
	WorkerCode     string
	KafkaBootstrap []string
	SASLUsername   string // optional
	SASLPassword   string // optional
}

// loadConfig reads all settings from the environment and fails fast listing
// every missing required variable at once. Env var names mirror the Java sample
// (BATCH_* family) plus KAFKA_* for broker settings.
func loadConfig() (config, error) {
	required := map[string]string{
		"BATCH_BASE_URL":   os.Getenv("BATCH_BASE_URL"),
		"BATCH_API_KEY":    os.Getenv("BATCH_API_KEY"),
		"BATCH_TENANT_ID":  os.Getenv("BATCH_TENANT_ID"),
		"BATCH_WORKER_CODE": os.Getenv("BATCH_WORKER_CODE"),
		"KAFKA_BOOTSTRAP":  os.Getenv("KAFKA_BOOTSTRAP"),
	}
	var missing []string
	for name, val := range required {
		if strings.TrimSpace(val) == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return config{}, fmt.Errorf("missing required env vars: %s", strings.Join(missing, ", "))
	}

	var brokers []string
	for _, b := range strings.Split(required["KAFKA_BOOTSTRAP"], ",") {
		if b = strings.TrimSpace(b); b != "" {
			brokers = append(brokers, b)
		}
	}

	return config{
		BaseURL:        required["BATCH_BASE_URL"],
		APIKey:         required["BATCH_API_KEY"],
		TenantID:       required["BATCH_TENANT_ID"],
		WorkerCode:     required["BATCH_WORKER_CODE"],
		KafkaBootstrap: brokers,
		// Optional SASL/SCRAM-SHA-512; PLAINTEXT when unset.
		SASLUsername: os.Getenv("KAFKA_SASL_USERNAME"),
		SASLPassword: os.Getenv("KAFKA_SASL_PASSWORD"),
	}, nil
}

// ---------------------------------------------------------------------------
// API-key auth transport
// ---------------------------------------------------------------------------

// authRoundTripper stamps the bearer API key on every outbound request.
type authRoundTripper struct {
	apiKey string
	next   http.RoundTripper
}

func (a *authRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	req.Header.Set("Authorization", "Bearer "+a.apiKey)
	return a.next.RoundTrip(req)
}

// newAuthedHTTPClient returns an http.Client that keeps the SDK's mandatory 10s
// timeout (§4 Go pit — never leave the client with no timeout) and injects the
// API-key header via a RoundTripper.
func newAuthedHTTPClient(apiKey string) *http.Client {
	return &http.Client{
		Timeout:   10 * time.Second,
		Transport: &authRoundTripper{apiKey: apiKey, next: http.DefaultTransport},
	}
}
