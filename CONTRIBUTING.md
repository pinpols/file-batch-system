# Contributing

## Scope

This repository is maintained as a multi-module Spring Boot batch platform.
Changes should stay aligned with the existing architecture, testing strategy,
and documentation contract.

## Before You Submit

- Run the relevant unit and integration tests for the module you changed.
- Update `docs/api/console-api-protocol.md` and `docs/api/console-api.openapi.yaml`
  when HTTP contracts change.
- Update the testing docs when test coverage or gate behavior changes.
- Keep Excel template changes aligned with the workbook contract under
  `docs/excel-templates/`.
- Do not introduce JPA/Hibernate dependencies.

## Recommended Checks

- `mvn -q -DskipTests compile`
- Targeted `mvn test` or `mvn verify` for the affected module
- `bash scripts/ci/run-full-regression.sh --help`
- `bash scripts/ci/check-console-openapi-paths.py`

## Commit Hygiene

- Keep commits focused and reviewable.
- Separate code changes, documentation changes, and generated artifacts when
  possible.
- Avoid committing local IDE state, build outputs, or machine-specific env files.

