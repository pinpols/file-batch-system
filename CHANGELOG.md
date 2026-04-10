# Changelog

All notable changes to this repository will be documented in this file.

## [Unreleased]

### Added

- Console API contract and OpenAPI spec
- Excel maintenance workflows for configurable console data
- Testing, deployment, and observability documentation
- Notification subscription management: channel CRUD (EMAIL/DINGTALK/WECOM/WEBHOOK/SMS), subscription rule CRUD, delivery logs, test notification (V49 migration + `ConsoleNotificationController`)
- Config approval flow: submit → approve / reject state machine for config releases with audit trail (V49 migration + `ConsoleConfigApprovalController`)
- Cross-environment config sync: export / preview / import config bundles between tenants with sync log tracking (V49 migration + `ConsoleConfigSyncController`)
- SLA report endpoint (`GET /api/console/dashboard/sla-report`)
- File error record CSV export (`GET /api/console/files/{fileId}/errors/export`)
- Job definition clone with field overrides (`POST /api/console/job-definitions/{id}/clone`)
- Excel import cross-reference validation (queue/calendar/window code existence checks)
- OpenAPI SDK generation profile (`openapi-codegen`) and CI script (`scripts/ci/generate-sdk.sh`)
- Outbox ops endpoints: stats / cleanup / republish

### Changed

- Repository-level environment handling was split into `.env.local`,
  `.env.test`, and `.env.prod` templates
- Root README now links to testing and API documentation indices

### Notes

- This project is still tracked as `1.0.0-SNAPSHOT` in Maven.
- For release notes, prefer linking commit ranges and generated test reports.
