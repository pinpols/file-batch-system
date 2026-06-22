package kafka

import "testing"

// TestMatchesDirectDispatch_WorkerTypeAgnostic pins the guarantee that one SDK
// worker subscription covers EVERY task type: the node-direct dispatch topic
// matcher keys only on the dispatch base prefix + `.node.<workerCode>` suffix,
// never on the <workerType> segment. This is why SDK e2e coverage needs ONE
// representative task type (echo) per language, not a language×worker-type matrix
// (see docs/sdk/local-e2e-coverage.md) — per-type dispatch is covered by the
// built-in workers' *E2eIT.
func TestMatchesDirectDispatch_WorkerTypeAgnostic(t *testing.T) {
	const wc = "w-1"
	cases := []struct {
		name  string
		topic string
		want  bool
	}{
		{"atomic node-direct", "batch.task.dispatch.atomic.node.w-1", true},
		{"import node-direct", "batch.task.dispatch.import.node.w-1", true},
		{"export node-direct", "batch.task.dispatch.export.node.w-1", true},
		{"process node-direct", "batch.task.dispatch.process.node.w-1", true},
		{"dispatch node-direct", "batch.task.dispatch.dispatch.node.w-1", true},
		{"other worker's node-direct", "batch.task.dispatch.atomic.node.w-2", false},
		{"base broadcast (not directed at us)", "batch.task.dispatch.atomic", false},
		{"tenant-suffixed (old wrong SDK scheme)", "batch.task.dispatch.atomic.acme", false},
		{"unrelated topic", "batch.task.result", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := matchesDirectDispatch(tc.topic, wc); got != tc.want {
				t.Fatalf("matchesDirectDispatch(%q,%q)=%v want %v", tc.topic, wc, got, tc.want)
			}
		})
	}
}
