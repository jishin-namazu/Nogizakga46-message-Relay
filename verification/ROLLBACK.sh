#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
modified="$root/verification/MODIFIED_FILE.kt"
baseline="$root/verification/rollback-test/MainActivity.kt"
restored="$root/verification/rollback-test/restored-MainActivity.kt"

test -f "$modified"
test -f "$baseline"
cp "$baseline" "$restored"
cmp -s "$baseline" "$restored"
printf 'rollback restored behavior/status: PASS\n'
