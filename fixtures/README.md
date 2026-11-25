# Codegen fixtures

Golden test cases for the Kotlin generator. Each folder holds:

- `schema.json`: the module definition (RawModuleDef V10, SATS-JSON), from stock SpacetimeDB 2.16.3
  `spacetimedb-standalone extract-schema`
- `expected/`: the Kotlin bindings the old Rust generator (SpacetimeDB fork, branch `kotlin-sdk`,
  upstream 2.15.0) produced for the same module

The new generator must turn `schema.json` into `expected/` exactly. The only exception is the CLI
version, which differs by build: the `// This was generated using spacetimedb cli version …` line and
`cliVersion` in `Module.kt`.

| Fixture | Source | Covers |
|---|---|---|
| `stuno` | Stuno app module (TypeScript) | the real consumer |
| `module-test` | fork `modules/module-test` (Rust) | the fork's snapshot module: scheduled tables, indexes, many types |
| `module-test-ts` | fork `modules/module-test-ts` | submodules, namespaces |
| `case-conversion` | fork `modules/sdk-test-case-conversion` (Rust) | `CaseConversionPolicy`, explicit names |
| `integration-tests` | `integration-tests/spacetimedb` (Rust) | the module the integration tests run against |

Captured once by `scripts/capture-fixtures.sh` (2026-11-25). The fork is not needed afterwards. Don't
regenerate `expected/` with the new generator: that would make the test pass by definition.
