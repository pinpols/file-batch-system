package kafka

import (
	"testing"

	kgo "github.com/segmentio/kafka-go"
)

func msg(topic string, partition int, offset int64) kgo.Message {
	return kgo.Message{Topic: topic, Partition: partition, Offset: offset}
}

// committable is the pure core of the withhold invariant: it must drop every
// message at or past its partition's withheld ceiling, and pass everything else
// through untouched, order preserved. Broker-free unit coverage of the offset
// rule the Java SDK gets from seek-back.
func TestCommittable(t *testing.T) {
	tp := func(topic string, p int) tpKey { return tpKey{topic: topic, partition: p} }

	cases := []struct {
		name     string
		msgs     []kgo.Message
		withheld map[tpKey]int64
		wantOff  []int64 // offsets expected to survive (committable), in order
	}{
		{
			name:    "no ceiling commits everything",
			msgs:    []kgo.Message{msg("a", 0, 0), msg("a", 0, 1)},
			wantOff: []int64{0, 1},
		},
		{
			name:     "withheld offset itself is never committable",
			msgs:     []kgo.Message{msg("a", 0, 5)},
			withheld: map[tpKey]int64{tp("a", 0): 5},
			wantOff:  nil,
		},
		{
			name:     "a later offset on the withheld partition cannot cross it",
			msgs:     []kgo.Message{msg("a", 0, 6), msg("a", 0, 7)},
			withheld: map[tpKey]int64{tp("a", 0): 5},
			wantOff:  nil,
		},
		{
			name:     "earlier offsets on the same partition still commit",
			msgs:     []kgo.Message{msg("a", 0, 3), msg("a", 0, 4), msg("a", 0, 5)},
			withheld: map[tpKey]int64{tp("a", 0): 5},
			wantOff:  []int64{3, 4},
		},
		{
			name:     "ceiling is scoped per partition",
			msgs:     []kgo.Message{msg("a", 0, 9), msg("a", 1, 9)},
			withheld: map[tpKey]int64{tp("a", 0): 5},
			wantOff:  []int64{9}, // only partition 1's offset survives
		},
		{
			name:     "ceiling is scoped per topic",
			msgs:     []kgo.Message{msg("a", 0, 9), msg("b", 0, 9)},
			withheld: map[tpKey]int64{tp("a", 0): 5},
			wantOff:  []int64{9}, // only topic b survives
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := committable(tc.msgs, tc.withheld)
			var offs []int64
			for _, m := range got {
				offs = append(offs, m.Offset)
			}
			if len(offs) != len(tc.wantOff) {
				t.Fatalf("committable offsets = %v, want %v", offs, tc.wantOff)
			}
			for i := range offs {
				if offs[i] != tc.wantOff[i] {
					t.Fatalf("committable offsets = %v, want %v", offs, tc.wantOff)
				}
			}
		})
	}
}

// committable must not alias / mutate the caller's slice backing array, since
// Commit() keeps a reference to pending.
func TestCommittable_DoesNotMutateInput(t *testing.T) {
	in := []kgo.Message{msg("a", 0, 3), msg("a", 0, 5), msg("a", 0, 4)}
	_ = committable(in, map[tpKey]int64{{topic: "a", partition: 0}: 5})
	wantOrder := []int64{3, 5, 4}
	for i, m := range in {
		if m.Offset != wantOrder[i] {
			t.Fatalf("input mutated: in[%d].Offset = %d, want %d", i, m.Offset, wantOrder[i])
		}
	}
}

// Withhold records the last-fetched record as the partition ceiling (lowest
// wins) and drops it (and anything at/past it) from pending so it can never be
// committed.
func TestConsumer_WithholdSetsCeilingAndDropsPending(t *testing.T) {
	c := &Consumer{withheld: map[tpKey]int64{}}

	// pending as if we fetched offset 7 then judged it foreign/bad.
	c.pending = []kgo.Message{msg("a", 0, 7)}
	c.Withhold()

	if got := c.withheld[tpKey{topic: "a", partition: 0}]; got != 7 {
		t.Fatalf("ceiling = %d, want 7", got)
	}
	if len(c.pending) != 0 {
		t.Fatalf("withheld record must be dropped from pending, got %d pending", len(c.pending))
	}

	// A second withhold at a LOWER offset must lower the ceiling.
	c.pending = []kgo.Message{msg("a", 0, 4)}
	c.Withhold()
	if got := c.withheld[tpKey{topic: "a", partition: 0}]; got != 4 {
		t.Fatalf("ceiling must drop to the lowest withheld offset, got %d, want 4", got)
	}

	// Withhold on empty pending is a no-op (no panic, ceiling unchanged).
	c.pending = nil
	c.Withhold()
	if got := c.withheld[tpKey{topic: "a", partition: 0}]; got != 4 {
		t.Fatalf("empty withhold changed the ceiling to %d", got)
	}
}
