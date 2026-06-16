// Nested module: the real Kafka consumer adapter (segmentio/kafka-go).
//
// Kept SEPARATE from the root batch-worker-sdk-go module so the root stays
// ZERO-dependency (the SDK core depends only on the client.Consumer SPI and
// never imports a broker library). The replace directive points the parent
// module path at ../ so this adapter can import the parent's client/protocol
// packages directly from source.
module github.com/pinpols/file-batch-system/batch-worker-sdk-go/kafka

go 1.25

require (
	github.com/pinpols/file-batch-system/batch-worker-sdk-go v0.0.0-00010101000000-000000000000
	github.com/segmentio/kafka-go v0.4.51
)

require (
	github.com/klauspost/compress v1.15.9 // indirect
	github.com/pierrec/lz4/v4 v4.1.15 // indirect
	github.com/xdg-go/pbkdf2 v1.0.0 // indirect
	github.com/xdg-go/scram v1.1.2 // indirect
	github.com/xdg-go/stringprep v1.0.4 // indirect
	golang.org/x/text v0.23.0 // indirect
)

replace github.com/pinpols/file-batch-system/batch-worker-sdk-go => ../
