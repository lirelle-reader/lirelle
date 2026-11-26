#!/usr/bin/env bash
# Fails when production code builds an HTTP client of its own instead of starting from the app's public client.
# Every request must name Lirelle (its User-Agent) and share what the public client sets: build clients from the
# @PublicClient or @AuthenticatedClient client with newBuilder().
# Line by line: a call split over several lines is not seen.
set -euo pipefail
cd "$(dirname "$0")/.."

allowed='core/network/src/main/kotlin/app/tfdn/lirelle/core/network/PublicClient.kt'
# OkHttp clients from scratch (also through an imported Builder); Coil's fetcher with a client of its own; Media3's
# own HTTP stack, chosen explicitly or by a DefaultDataSource.Factory given no upstream; the JDK's connections.
pattern='OkHttpClient[[:space:]]*\.[[:space:]]*Builder[[:space:]]*\(|OkHttpClient[[:space:]]*\([[:space:]]*\)'
pattern+='|import okhttp3\.OkHttpClient\.Builder|OkHttpNetworkFetcherFactory[[:space:]]*\([[:space:]]*\)'
pattern+='|DefaultHttpDataSource|DefaultDataSourceFactory|DefaultDataSource\.Factory\([^,()]*\)'
pattern+='|\.openConnection\(|\.openStream\('

# core/testing is test support only (sample data and fakes, never shipped), so it is not scanned.
roots=()
while IFS= read -r root; do roots+=("$root"); done < <(
  find app core feature source -type d -path '*/src/main' -not -path '*/build/*' -not -path 'core/testing/*' 2>/dev/null
)
if (( ${#roots[@]} == 0 )); then
  echo "error: no production sources found (*/src/main under app, core, feature, source)." >&2
  exit 1
fi

hits=$(grep -rnE "$pattern" --include='*.kt' "${roots[@]}" | grep -v "^$allowed:" || true)
if [[ -n "$hits" ]]; then
  echo "$hits" >&2
  echo "error: an HTTP client built from scratch; start from the @PublicClient (or @AuthenticatedClient) client" \
    "with newBuilder(), so that the request names Lirelle. The only client built from scratch is in $allowed." >&2
  exit 1
fi
echo "One HTTP client, the public one."
