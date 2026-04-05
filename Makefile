.PHONY: observability-up observability-down observability-status observability-logs observability-inspect

observability-up:
	./scripts/docker/up-observability.sh

observability-down:
	./scripts/docker/down-observability.sh

observability-status:
	./scripts/docker/observability/status.sh

observability-logs:
	./scripts/docker/observability/logs.sh

observability-inspect:
	./scripts/docker/observability/inspect.sh

