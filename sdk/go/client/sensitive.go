package client

import (
	"fmt"
	"strings"

	"github.com/pinpols/file-batch-system/batch-worker-sdk-go/protocol"
)

// SensitiveValidator implements the SensitiveDataValidator equivalent
// (byo-sdk-guide §1.8): credentials must travel via env, never in a register
// body or dispatch parameters. It scans map keys against
// protocol.SensitiveKeywords (plus a tenant-extensible deny-list); a sensitive
// key carrying a NON-EMPTY value is a violation.
//
// Two enforcement points differ by phase:
//   - register body  -> ValidateRegister returns an error (fail-fast at startup).
//   - dispatch params -> ValidateParameters returns SECURITY_REJECTED so the
//     task is reported failed rather than crashing the worker.
type SensitiveValidator struct {
	// keywords is the effective deny-list (shared protocol set + extensions),
	// stored lower-cased and stripped of separators for matching.
	keywords []string
}

// NewSensitiveValidator builds a validator seeded with protocol.SensitiveKeywords
// and any tenant deny-list extensions.
func NewSensitiveValidator(extraDenyList ...string) *SensitiveValidator {
	v := &SensitiveValidator{}
	for _, k := range protocol.SensitiveKeywords {
		v.keywords = append(v.keywords, normalizeKey(k))
	}
	v.AddDenyList(extraDenyList...)
	return v
}

// AddDenyList registers additional sensitive keywords (the §1.8 extension hook).
func (v *SensitiveValidator) AddDenyList(keywords ...string) {
	for _, k := range keywords {
		n := normalizeKey(k)
		if n != "" && !containsString(v.keywords, n) {
			v.keywords = append(v.keywords, n)
		}
	}
}

// normalizeKey lower-cases and strips '_' / '-' / '.' so "API_KEY", "api-key"
// and "apikey" all collapse to the same token.
func normalizeKey(k string) string {
	k = strings.ToLower(k)
	k = strings.NewReplacer("_", "", "-", "", ".", "").Replace(k)
	return k
}

// isSensitiveKey reports whether key matches any deny-list keyword (substring on
// the normalized form, matching the Java validator's contains semantics).
func (v *SensitiveValidator) isSensitiveKey(key string) bool {
	n := normalizeKey(key)
	for _, kw := range v.keywords {
		if strings.Contains(n, kw) {
			return true
		}
	}
	return false
}

// scan returns the first offending key whose value is sensitive + non-empty.
// Recurses into nested maps so parameters.credentials.password is caught.
func (v *SensitiveValidator) scan(m map[string]any) (string, bool) {
	for k, val := range m {
		if nested, ok := val.(map[string]any); ok {
			if bad, found := v.scan(nested); found {
				return bad, true
			}
			continue
		}
		if v.isSensitiveKey(k) && !isEmptyValue(val) {
			return k, true
		}
	}
	return "", false
}

// isEmptyValue reports whether a value should be treated as "blank" (allowed —
// credential goes via env, so an empty placeholder key is fine).
func isEmptyValue(val any) bool {
	switch x := val.(type) {
	case nil:
		return true
	case string:
		return strings.TrimSpace(x) == ""
	default:
		return false
	}
}

// ValidateRegister fails fast on any sensitive non-empty field in the register
// fingerprint attributes (§1.8 register path).
func (v *SensitiveValidator) ValidateRegister(req RegisterRequest) error {
	if bad, found := v.scan(req.Attributes); found {
		return fmt.Errorf("sensitive credential %q must travel via env, not the register body", bad)
	}
	return nil
}

// ValidateParameters checks dispatch effective parameters. A violation yields a
// SECURITY_REJECTED TaskResult (the second return is true when rejected) so the
// caller reports the task failed rather than executing it.
func (v *SensitiveValidator) ValidateParameters(params map[string]any) (TaskResult, bool) {
	if bad, found := v.scan(params); found {
		return Fail(protocol.ErrorCodeSecurityRejected,
			fmt.Sprintf("sensitive credential %q present in task parameters", bad)), true
	}
	return TaskResult{}, false
}

func containsString(s []string, t string) bool {
	for _, x := range s {
		if x == t {
			return true
		}
	}
	return false
}
