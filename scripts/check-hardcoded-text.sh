#!/usr/bin/env bash
# Fails when Compose code passes a string literal as user-visible text.
# User-visible text must come from string resources (English and French).
set -euo pipefail
cd "$(dirname "$0")/.."

# core/testing is test support only (sample data and fakes, never shipped), so it is not scanned.
pattern='\bText\(\s*"|\b(text|label|title|placeholder|contentDescription)\s*=\s*"'
if grep -rnE "$pattern" --include='*.kt' \
    $(find app core feature -type d -path '*/src/main' -not -path 'core/testing/*' 2>/dev/null); then
  echo "error: hard-coded UI text found; use stringResource()." >&2
  exit 1
fi
echo "No hard-coded UI text."
