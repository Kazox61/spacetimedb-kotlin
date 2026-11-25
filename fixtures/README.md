# Codegen fixtures

Golden test cases for the Kotlin generator. Each folder holds:

- `schema.json`: the module definition (RawModuleDef V10, SATS-JSON), from stock SpacetimeDB 2.16.3
  `spacetimedb-standalone extract-schema`
- `expected/`: the Kotlin bindings the old Rust generator (SpacetimeDB fork, branch `kotlin-sdk`,
  upstream 2.15.0) produced for the same module
- `model.json`: what the old generator read from upstream's validated `ModuleDef`, dumped by
  `scripts/model-dump`, plus `convert_case` results for every identifier. The oracle for the
  normalizer (`NormalizerOracleTest`).

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

Captured by `scripts/capture-fixtures.sh` (2026-11-25). The fork is needed only to recapture. Don't
regenerate `expected/` with the new generator: that would make the test pass by definition.

**Order.** `extract-schema` writes `Types`, `Tables`, `ExplicitNames`, `Schedules`, `Outboxes` and
nested submodule sections in hash order, which changes from run to run; recapturing reorders them
without changing their content. `Reducers`, `Procedures` and `Views` keep the module's order. The old
generator's output doesn't depend on the hash order (it sorts tables, views, types, procedures and
indexes by name, and keeps reducers in schema order), and the new one must not either.
