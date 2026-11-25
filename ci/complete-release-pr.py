#!/usr/bin/env python3
"""Completes release-please's pull request with what the stores read in the tagged commit (#26).

Run on the release PR's branch, with main fetched as origin/main:
- gradle.properties gets lirelle.versionCode, the versionCode the release will have once squashed into main (F-Droid's
  update check reads it; the build itself still counts main's commits, and the release workflow checks both agree);
- fastlane/metadata/android/en-US/changelogs/<versionCode>.txt gets the release's notes as plain text, at most 500
  characters (Google Play's and F-Droid's limit); a changelog this PR added under an older code is removed.
Prints what it changed; changes nothing when all is up to date.
"""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPERTIES = ROOT / 'gradle.properties'
CHANGELOGS = ROOT / 'fastlane/metadata/android/en-US/changelogs'
# What users care about; release-please's other sections (docs, a version forced with Release-As) are left out.
SECTIONS = ('Features', 'Fixes', 'Performance')
LIMIT = 500


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def version_code():
    # The squash merge of this pull request adds one commit to main's first-parent line.
    return 1000 + int(git('rev-list', '--count', '--first-parent', 'origin/main')) + 1


def write_code(code):
    text = PROPERTIES.read_text()
    line = f'lirelle.versionCode={code}'
    if re.search(r'^lirelle\.versionCode=\d+$', text, re.M):
        updated = re.sub(r'^lirelle\.versionCode=\d+$', line, text, flags=re.M)
    else:
        updated = text.replace(
            '# x-release-please-end\n',
            '# x-release-please-end\n'
            "# The release's versionCode, written in release-please's pull request by .github/workflows/release-pr.yml\n"
            '# for F-Droid\'s update check. The build counts main\'s commits; the release workflow checks both agree.\n'
            f'{line}\n', 1)
    if updated != text:
        PROPERTIES.write_text(updated)
        print(f'gradle.properties: {line}')


def notes(version):
    """The release's notes from CHANGELOG.md, as a plain list under their section's name."""
    changelog = (ROOT / 'CHANGELOG.md').read_text()
    match = re.search(rf'^## \[?{re.escape(version)}\]?.*?$(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match:
        raise SystemExit(f'no section {version} in CHANGELOG.md')
    lines, section = [], None
    for raw in match.group(1).splitlines():
        heading = re.match(r'^### (.+)$', raw)
        if heading:
            section = heading.group(1).strip()
            if section in SECTIONS:
                lines.append(section)
            continue
        item = re.match(r'^\* (.+)$', raw)
        if item and section in SECTIONS:
            text = re.sub(r'\s*\(\[[^\]]*\]\([^)]*\)\)', '', item.group(1))  # ([#12](…)) ([4da75f4](…))
            text = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', text)  # any other link: its words
            text = re.sub(r'^\*\*[^*]+:\*\*\s*', '', text).strip()  # a scope in bold
            lines.append(f'• {text[0].upper()}{text[1:]}')
    kept = []
    for line in lines:
        if len('\n'.join(kept + [line])) > LIMIT:
            break
        kept.append(line)
    # A section's name left with none of its lines under it goes.
    while kept and not kept[-1].startswith('•'):
        kept.pop()
    return '\n'.join(kept) + '\n' if kept else 'Fixes and improvements.\n'


def write_changelog(code, version):
    CHANGELOGS.mkdir(parents=True, exist_ok=True)
    on_main = set(git('ls-tree', '--name-only', 'origin/main', f'{CHANGELOGS.relative_to(ROOT)}/').splitlines())
    for old in CHANGELOGS.glob('*.txt'):
        if old.stem != str(code) and str(old.relative_to(ROOT)) not in on_main:
            old.unlink()
            print(f'removed {old.relative_to(ROOT)}')
    path = CHANGELOGS / f'{code}.txt'
    text = notes(version)
    if not path.exists() or path.read_text() != text:
        path.write_text(text)
        print(f'{path.relative_to(ROOT)}:\n{text}')


def main():
    version = json.loads((ROOT / '.release-please-manifest.json').read_text())['.']
    code = version_code()
    write_code(code)
    write_changelog(code, version)


if __name__ == '__main__':
    main()
