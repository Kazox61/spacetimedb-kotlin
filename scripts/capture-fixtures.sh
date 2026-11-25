#!/usr/bin/env bash
# One-time capture of golden fixtures from the old Rust generator in the SpacetimeDB fork.
#
# For each fixture module, writes:
#   fixtures/<name>/schema.json  - module definition from the stock `spacetimedb-standalone extract-schema`
#   fixtures/<name>/expected/    - Kotlin bindings from the fork CLI's `generate --lang kotlin`
#   fixtures/<name>/model.json   - the fork's validated ModuleDef as the generator reads it (scripts/model-dump),
#                                  the oracle for the Kotlin normalizer
#
# Needs: the fork at $SPACETIMEDB_FORK (default ~/dev/SpacetimeDB) with `cargo build -p spacetimedb-cli` done,
# the fork's TypeScript library built (`pnpm run build` in crates/bindings-typescript),
# and a stock SpacetimeDB install (`spacetime`, `spacetimedb-standalone`).
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
fork="${SPACETIMEDB_FORK:-$HOME/dev/SpacetimeDB}"
stuno="${STUNO_REPO:-$HOME/dev/student-notes}"
fork_cli="$fork/target/debug/spacetimedb-cli"
standalone="${SPACETIMEDB_STANDALONE:-$HOME/.local/share/spacetime/bin/current/spacetimedb-standalone}"

[ -x "$fork_cli" ] || { echo "fork CLI missing: $fork_cli (cargo build --locked -p spacetimedb-cli)" >&2; exit 1; }

# The oracle tool, built against the fork's crates with the fork's exact dependency versions.
cp "$fork/Cargo.lock" "$repo/scripts/model-dump/Cargo.lock"
# Run from its folder: rustup picks the pinned toolchain by working directory.
(cd "$repo/scripts/model-dump" && CARGO_TARGET_DIR="$fork/target" cargo build -q)
model_dump="$fork/target/debug/model-dump"

# name, module dir, host type (js | wasm)
fixtures=(
  "stuno               $stuno/spacetimedb                              js"
  "module-test         $fork/modules/module-test                       wasm"
  "module-test-ts      $fork/modules/module-test-ts                    js"
  "case-conversion     $fork/modules/sdk-test-case-conversion          wasm"
  "integration-tests   $fork/sdks/kotlin/integration-tests/spacetimedb  wasm"  # fork copy: its path dep only resolves there
)

for entry in "${fixtures[@]}"; do
  read -r name dir host <<<"$entry"
  echo "== $name ($dir)"
  out="$repo/fixtures/$name"
  rm -rf "$out"
  mkdir -p "$out"

  spacetime build --module-path "$dir" >/dev/null
  if [ "$host" = js ]; then
    artifact="$dir/dist/bundle.js"
    gen_input=(--js-path "$artifact")
  else
    # <crate name>.wasm, in the module's own target dir or the fork workspace's.
    crate="$(sed -n 's/^name *= *"\(.*\)"/\1/p' "$dir/Cargo.toml" | head -n1 | tr - _)"
    artifact=""
    for target in "$dir/target" "$fork/target"; do
      candidate="$target/wasm32-unknown-unknown/release/$crate.wasm"
      if [ -f "$candidate" ]; then artifact="$candidate"; break; fi
    done
    gen_input=(--bin-path "$artifact")
  fi
  [ -f "$artifact" ] || { echo "no build output for $name" >&2; exit 1; }

  "$standalone" extract-schema --host-type "$host" "$artifact" >"$out/schema.json"
  "$fork_cli" generate --lang kotlin "${gen_input[@]}" --out-dir "$out/expected" --yes --no-config >/dev/null
  "$model_dump" "$out/schema.json" >"$out/model.json"
  echo "   $(find "$out/expected" -type f | wc -l | tr -d ' ') files"
done
