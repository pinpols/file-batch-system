package conformance

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strings"
	"testing"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// §1.2 conformance runner: load all 12 contract fixtures, drive the decision
// core from each fixture's given/when, and assert that EVERY field present in
// then.expect deep-equals the computed Decision's same field.
//
// The dispatch picks a decision function from the protocol shape of `when`
// (channel / path / status) — NOT from then.expect. The decision functions
// contain the real logic; this runner only routes inputs and flattens outputs
// into the closed then.expect vocabulary.

const (
	fixturesDir          = "../../docs/api/sdk-contract-fixtures"
	expectedFixtureCount = 12
)

type fixture struct {
	Scenario string `json:"scenario"`
	Given    struct {
		Config map[string]any `json:"config"`
		State  map[string]any `json:"state"`
	} `json:"given"`
	When struct {
		Channel        string          `json:"channel"`
		Method         string          `json:"method"`
		Path           string          `json:"path"`
		Body           map[string]any  `json:"body"`
		ResponseStatus *int            `json:"responseStatus"`
		ResponseBody   json.RawMessage `json:"responseBody"`
	} `json:"when"`
	Then struct {
		Expect map[string]any `json:"expect"`
	} `json:"then"`
}

// compute routes a fixture to the appropriate decision function based on the
// protocol shape of `when`, exactly like the TS runner.
func compute(fx fixture) (protocol.Decision, error) {
	when := fx.When

	// ----- Kafka receive → capacity backpressure -----
	if when.Channel == "kafka" {
		inFlight := numFromAny(fx.Given.State["inFlight"], 0)
		maxConcurrent := numFromAny(fx.Given.Config["maxConcurrentTasks"], int(^uint(0)>>1))
		return protocol.DecideBackpressure(inFlight, maxConcurrent), nil
	}

	// ----- HTTP -----
	path := when.Path
	status := 0
	if when.ResponseStatus != nil {
		status = *when.ResponseStatus
	}

	switch {
	case strings.HasSuffix(path, "/register"):
		// idempotent reuse signal: platform already had a (tenant, workerCode)
		// record — fixtures encode this via given.state describing prior existence.
		idempotent := fx.Given.State != nil
		return protocol.DecideRegister(idempotent), nil

	case strings.Contains(path, "/heartbeat"):
		var resp protocol.HeartbeatResponse
		if err := unmarshalResponse(when.ResponseBody, &resp); err != nil {
			return protocol.Decision{}, err
		}
		return protocol.ApplyHeartbeatDirective(resp), nil

	case strings.Contains(path, "/deactivate"):
		// graceful stop. stop timeout: prefer config, else default 30s grace.
		timeoutMs := numFromAny(fx.Given.Config["stopTimeoutMs"], 0)
		if timeoutMs == 0 {
			timeoutMs = 30000
		}
		return protocol.PlanStop(timeoutMs), nil

	case strings.Contains(path, "/renew"):
		var resp protocol.RenewResponse
		if err := unmarshalResponse(when.ResponseBody, &resp); err != nil {
			return protocol.Decision{}, err
		}
		return protocol.ApplyRenew(resp), nil

	case strings.Contains(path, "/claim") || strings.Contains(path, "/report"):
		baseMs := numFromAny(fx.Given.Config["retryBaseDelayMs"], 0)
		attempts := numFromAny(fx.Given.Config["retryMaxAttempts"], 0)
		return protocol.ClassifyHTTP(status, 0, baseMs, attempts), nil
	}

	return protocol.Decision{}, fmt.Errorf("no decision route for fixture %s (path=%s)", fx.Scenario, path)
}

func TestAllFixturesPresent(t *testing.T) {
	files := fixtureFiles(t)
	if len(files) != expectedFixtureCount {
		t.Fatalf("expected %d fixtures, found %d: %v", expectedFixtureCount, len(files), files)
	}
}

func TestConformance(t *testing.T) {
	for _, file := range fixtureFiles(t) {
		file := file
		t.Run(filepath.Base(file), func(t *testing.T) {
			fx := loadFixture(t, file)

			if len(fx.Then.Expect) == 0 {
				t.Fatalf("%s: then.expect is empty", file)
			}

			computed, err := compute(fx)
			if err != nil {
				t.Fatalf("%s [%s]: compute error: %v", file, fx.Scenario, err)
			}

			// Flatten the Decision into the then.expect field vocabulary via JSON
			// (omitempty drops unset pointer/slice fields), then assert every field
			// PRESENT in then.expect; absent fields are unconstrained.
			actual := decisionToMap(t, computed)

			for field, expectedValue := range fx.Then.Expect {
				got, present := actual[field]
				if !present {
					t.Errorf("%s [%s]: decision core did not produce field %q (computed=%v)",
						file, fx.Scenario, field, actual)
					continue
				}
				if !jsonEqual(got, expectedValue) {
					t.Errorf("%s [%s]: field %q mismatch — computed %v, expected %v",
						file, fx.Scenario, field, got, expectedValue)
				}
			}
		})
	}
}

// --- helpers ---------------------------------------------------------------

func fixtureFiles(t *testing.T) []string {
	t.Helper()
	entries, err := os.ReadDir(fixturesDir)
	if err != nil {
		t.Fatalf("read fixtures dir %s: %v", fixturesDir, err)
	}
	numericJSON := regexp.MustCompile(`^\d.*\.json$`)
	var files []string
	for _, e := range entries {
		if !e.IsDir() && numericJSON.MatchString(e.Name()) {
			files = append(files, filepath.Join(fixturesDir, e.Name()))
		}
	}
	sort.Strings(files)
	return files
}

func loadFixture(t *testing.T, path string) fixture {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read fixture %s: %v", path, err)
	}
	var fx fixture
	if err := json.Unmarshal(data, &fx); err != nil {
		t.Fatalf("parse fixture %s: %v", path, err)
	}
	return fx
}

// decisionToMap marshals the Decision and re-decodes it into a generic map so
// only fields the struct actually emitted (omitempty) appear.
func decisionToMap(t *testing.T, d protocol.Decision) map[string]any {
	t.Helper()
	raw, err := json.Marshal(d)
	if err != nil {
		t.Fatalf("marshal decision: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("unmarshal decision: %v", err)
	}
	return m
}

// unmarshalResponse decodes a fixture responseBody, treating JSON null / absent
// as an empty struct.
func unmarshalResponse(raw json.RawMessage, v any) error {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	return json.Unmarshal(raw, v)
}

func numFromAny(v any, def int) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	default:
		return def
	}
}

// jsonEqual compares two values via a JSON round-trip so numeric/slice types
// from fixture (float64, []any) compare equal to the decision map equivalents.
func jsonEqual(a, b any) bool {
	ab, err1 := json.Marshal(a)
	bb, err2 := json.Marshal(b)
	if err1 != nil || err2 != nil {
		return reflect.DeepEqual(a, b)
	}
	var av, bv any
	_ = json.Unmarshal(ab, &av)
	_ = json.Unmarshal(bb, &bv)
	return reflect.DeepEqual(av, bv)
}
