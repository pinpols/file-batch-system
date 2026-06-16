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
	fixturesDir          = "../../../docs/api/sdk-contract-fixtures"
	expectedFixtureCount = 22
)

// requestSideKeys are then.expect keys handled by the request builder, not the
// response→reaction decision core.
var requestSideKeys = map[string]bool{
	"requestBodyIncludes": true,
	"requestBodyExcludes": true,
	"requestHeaders":      true,
}

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
		// error statuses classify by §B (404 give-up, 5xx backoff, ...);
		// a 2xx renew applies the cancel directive from the response body.
		if status >= 400 {
			clientErrorCount := numFromAny(fx.Given.State["clientErrorCount"], 0)
			return protocol.ClassifyHTTP(status, clientErrorCount, 0, 0), nil
		}
		var resp protocol.RenewResponse
		if err := unmarshalResponse(when.ResponseBody, &resp); err != nil {
			return protocol.Decision{}, err
		}
		return protocol.ApplyRenew(resp), nil

	case strings.Contains(path, "/claim") || strings.Contains(path, "/report"):
		baseMs := numFromAny(fx.Given.Config["retryBaseDelayMs"], 0)
		attempts := numFromAny(fx.Given.Config["retryMaxAttempts"], 0)
		clientErrorCount := numFromAny(fx.Given.State["clientErrorCount"], 0)
		return protocol.ClassifyHTTP(status, clientErrorCount, baseMs, attempts), nil
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

			// Request-side assertions (body includes/excludes, header regexes)
			// are driven by the request builder, not the decision core.
			if hasRequestSide(fx.Then.Expect) {
				assertRequestSide(t, fx)
			}

			// schemaAccept (§A) is asserted on the received kafka message.
			if want, ok := fx.Then.Expect["schemaAccept"]; ok {
				assertSchemaAccept(t, fx, want)
			}

			// Remaining response-side fields come from the decision core.
			if !hasDecisionFields(fx.Then.Expect) {
				return
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
				if requestSideKeys[field] || field == "schemaAccept" {
					continue
				}
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

// hasRequestSide reports whether the expect block has any request-side key.
func hasRequestSide(expect map[string]any) bool {
	for k := range expect {
		if requestSideKeys[k] {
			return true
		}
	}
	return false
}

// hasDecisionFields reports whether the expect block has any field produced by
// the response→reaction decision core (i.e. not request-side and not schemaAccept).
func hasDecisionFields(expect map[string]any) bool {
	for k := range expect {
		if !requestSideKeys[k] && k != "schemaAccept" {
			return true
		}
	}
	return false
}

// specFromState re-marshals given.state.request into a typed RequestSpec.
func specFromState(t *testing.T, fx fixture) protocol.RequestSpec {
	t.Helper()
	raw, ok := fx.Given.State["request"]
	if !ok {
		t.Fatalf("%s: request-side fixture missing given.state.request", fx.Scenario)
	}
	blob, err := json.Marshal(raw)
	if err != nil {
		t.Fatalf("%s: marshal request spec: %v", fx.Scenario, err)
	}
	var spec protocol.RequestSpec
	if err := json.Unmarshal(blob, &spec); err != nil {
		t.Fatalf("%s: unmarshal request spec: %v", fx.Scenario, err)
	}
	return spec
}

// assertRequestSide builds the outgoing request and asserts requestBodyIncludes
// (deep subset), requestBodyExcludes (absent keys), requestHeaders (regex match).
func assertRequestSide(t *testing.T, fx fixture) {
	t.Helper()
	spec := specFromState(t, fx)
	cfg := protocol.RequestBuildConfig{
		TenantID:   strFromAny(fx.Given.Config["tenantId"]),
		WorkerCode: strFromAny(fx.Given.Config["workerCode"]),
		APIKey:     strFromAny(fx.Given.Config["apiKey"]),
	}
	req, err := protocol.BuildRequest(spec, cfg)
	if err != nil {
		t.Fatalf("%s: build request: %v", fx.Scenario, err)
	}

	if inc, ok := fx.Then.Expect["requestBodyIncludes"].(map[string]any); ok {
		for k, v := range inc {
			got, present := req.Body[k]
			if !present {
				t.Errorf("%s: outgoing body missing key %q (body=%v)", fx.Scenario, k, req.Body)
				continue
			}
			if !jsonEqual(got, v) {
				t.Errorf("%s: body[%q] mismatch — got %v, want %v", fx.Scenario, k, got, v)
			}
		}
	}
	if exc, ok := fx.Then.Expect["requestBodyExcludes"].([]any); ok {
		for _, k := range exc {
			key := strFromAny(k)
			if _, present := req.Body[key]; present {
				t.Errorf("%s: outgoing body must NOT contain key %q (body=%v)", fx.Scenario, key, req.Body)
			}
		}
	}
	if hdrs, ok := fx.Then.Expect["requestHeaders"].(map[string]any); ok {
		for name, pat := range hdrs {
			value, present := req.Headers[name]
			if !present {
				t.Errorf("%s: outgoing headers missing %q", fx.Scenario, name)
				continue
			}
			re, err := regexp.Compile(strFromAny(pat))
			if err != nil {
				t.Fatalf("%s: bad header regex %q: %v", fx.Scenario, pat, err)
			}
			if !re.MatchString(value) {
				t.Errorf("%s: header %q=%q does not match /%s/", fx.Scenario, name, value, pat)
			}
		}
	}
}

// assertSchemaAccept classifies the kafka message's schemaVersion (§A).
func assertSchemaAccept(t *testing.T, fx fixture, want any) {
	t.Helper()
	version := strFromAny(fx.When.Body["schemaVersion"])
	got := protocol.ClassifySchemaVersion(version) == "accept"
	if got != (want == true) {
		t.Errorf("%s: schemaAccept mismatch — got %v, want %v (version=%q)",
			fx.Scenario, got, want, version)
	}
}

func strFromAny(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
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
