#!/usr/bin/env bash
# Fails when production code builds an HTTP client of its own instead of starting from the app's public client.
# Every request must name Lirelle (its User-Agent) and share what the public client sets: build clients from the
# @PublicClient or @AuthenticatedClient client with newBuilder().
set -euo pipefail
cd "$(dirname "$0")/.."

allowed='core/network/src/main/kotlin/app/tfdn/lirelle/core/network/PublicClient.kt'
# OkHttp clients from scratch; Media3's own HTTP stack, chosen explicitly or by a DefaultDataSource.Factory given no
# upstream; the JDK's connections.
pattern='OkHttpClient\.Builder\(\)|OkHttpClient\(\)|DefaultHttpDataSource|DefaultDataSource\.Factory\([^,()]*\)|\.openConnection\('

mapfile -t roots < <(find app core feature source -type d -path '*/src/main' -not -path '*/build/*' 2>/dev/null)
hits=$(grep -rnE "$pattern" --include='*.kt' "${roots[@]}" | grep -v "^$allowed:" || true)
if [[ -n "$hits" ]]; then
  echo "$hits" >&2
  echo "error: an HTTP client built from scratch; start from the @PublicClient (or @AuthenticatedClient) client" \
    "with newBuilder(), so that the request names Lirelle. The only client built from scratch is in $allowed." >&2
  exit 1
fi
echo "One HTTP client, the public one."
