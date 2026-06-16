package protocol

import (
	"os"
	"reflect"
	"regexp"
	"strings"
	"testing"
)

// §1.1 parity guard: assert the Go constants deep-equal the YAML source of truth.
// Uses a tiny hand-rolled parser for the simple `key:\n  - value` list structure
// in sdk-shared-constants.yaml (NO yaml dependency — zero runtime deps).

const yamlRelPath = "../../docs/api/sdk-shared-constants.yaml"

var (
	listItemRe = regexp.MustCompile(`^\s+-\s+(.+?)\s*$`)
	topKeyRe   = regexp.MustCompile(`^([A-Za-z_][\w-]*):\s*(.*)$`)
	commentRe  = regexp.MustCompile(`\s+#.*$`)
)

// parseSimpleYamlLists parses only top-level `key:` blocks whose value is a YAML
// block list of scalars. Returns key -> []string. Inline `key: []` -> empty.
func parseSimpleYamlLists(text string) map[string][]string {
	result := map[string][]string{}
	currentKey := ""

	for _, raw := range strings.Split(text, "\n") {
		line := commentRe.ReplaceAllString(strings.TrimRight(raw, "\r"), "")
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}

		if m := listItemRe.FindStringSubmatch(line); m != nil && currentKey != "" {
			result[currentKey] = append(result[currentKey], unquote(m[1]))
			continue
		}

		if m := topKeyRe.FindStringSubmatch(line); m != nil {
			key := m[1]
			inline := strings.TrimSpace(m[2])
			switch inline {
			case "":
				currentKey = key
				if _, ok := result[key]; !ok {
					result[key] = []string{}
				}
			case "[]":
				currentKey = ""
				result[key] = []string{}
			default:
				currentKey = "" // scalar value, ignore
			}
			continue
		}

		// any other unindented content ends the current list
		if len(line) > 0 && line[0] != ' ' && line[0] != '\t' {
			currentKey = ""
		}
	}
	return result
}

func unquote(s string) string {
	if len(s) >= 2 {
		if (s[0] == '"' && s[len(s)-1] == '"') || (s[0] == '\'' && s[len(s)-1] == '\'') {
			return s[1 : len(s)-1]
		}
	}
	return s
}

func loadYamlLists(t *testing.T) map[string][]string {
	t.Helper()
	data, err := os.ReadFile(yamlRelPath)
	if err != nil {
		t.Fatalf("read %s: %v", yamlRelPath, err)
	}
	return parseSimpleYamlLists(string(data))
}

func TestConstantsParity(t *testing.T) {
	lists := loadYamlLists(t)

	cases := []struct {
		key string
		got []string
	}{
		{"schema_versions_supported", SupportedSchemaVersions},
		{"worker_runtime_states", WorkerRuntimeStates},
		{"sensitive_keywords", SensitiveKeywords},
		{"task_statuses", TaskStatuses},
	}

	for _, c := range cases {
		want, ok := lists[c.key]
		if !ok {
			t.Errorf("YAML missing list key %q", c.key)
			continue
		}
		if !reflect.DeepEqual(c.got, want) {
			t.Errorf("%s parity: Go=%v YAML=%v", c.key, c.got, want)
		}
	}
}
