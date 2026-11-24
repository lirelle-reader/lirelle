#!/usr/bin/env python3
"""Zips each folder of books/src/ into books/out/<name>.epub.

The `mimetype` entry comes first and is stored, as the EPUB container format
asks. Entries are sorted and carry a fixed date, so building twice gives the
same bytes. A folder holding a `NOT_A_ZIP` file is copied as it is: that is the
book that is not an archive at all.
"""
import shutil
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
OUT = HERE / "out"
FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def add(archive, root, path, method):
    info = zipfile.ZipInfo(path.relative_to(root).as_posix(), FIXED_DATE)
    info.compress_type = method
    info.external_attr = 0o644 << 16
    archive.writestr(info, path.read_bytes())


def build(root):
    target = OUT / f"{root.name}.epub"
    fake = root / "NOT_A_ZIP"
    if fake.exists():
        shutil.copyfile(fake, target)
        return target
    mimetype = root / "mimetype"
    if not mimetype.exists():
        sys.exit(f"{root.name}: no mimetype file")
    with zipfile.ZipFile(target, "w") as archive:
        add(archive, root, mimetype, zipfile.ZIP_STORED)
        for path in sorted(p for p in root.rglob("*") if p.is_file() and p != mimetype):
            add(archive, root, path, zipfile.ZIP_DEFLATED)
    return target


def main():
    OUT.mkdir(exist_ok=True)
    for root in sorted(p for p in SRC.iterdir() if p.is_dir()):
        print(build(root).relative_to(HERE))


if __name__ == "__main__":
    main()
