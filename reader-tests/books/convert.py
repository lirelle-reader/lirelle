#!/usr/bin/env python3
"""Turns books/out/basic.epub into the books of the other formats the reader opens.

  basic.mobi      the old Mobipocket format (MOBI 6)
  basic.azw3      KF8
  basic.fb2       FictionBook
  protected.mobi  basic.mobi with its header saying its text is encrypted
  damaged.mobi    the head of basic.mobi, cut before its text
  damaged.fb2     the head of basic.fb2, cut in the middle of its XML
  notes.fb2       a FictionBook written here, with a body of notes and links to them

It needs Calibre's `ebook-convert`, which the tests do not have where they run:
run it only to change these books; its output is committed. Calibre stamps
what it writes, so building twice does not give the same bytes.
"""
import shutil
import struct
import subprocess
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"
SOURCE = OUT / "basic.epub"
CONVERSIONS = {
    "basic.mobi": ["--mobi-file-type", "old"],
    "basic.azw3": [],
    # A section and its title for each entry of the contents, as a book made as a FictionBook has.
    "basic.fb2": ["--sectionize", "toc"],
}
# Where the list of records starts in a Palm database, and where the header of
# its first record says how the text is encrypted (0: it is not).
RECORD_LIST = 78
ENCRYPTION = 12
DAMAGED_MOBI_BYTES = 2048

# A FictionBook as its own tools write one: the text in a first body, the notes
# in a second one, and links of the type `note` from the one to the other.
NOTES_FB2 = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
<title-info>
<genre>prose</genre>
<author><first-name>Lirelle</first-name><last-name>Tests</last-name></author>
<book-title>The Tide Tables</book-title>
<lang>en</lang>
</title-info>
<document-info>
<author><nickname>lirelle</nickname></author>
<date value="2026-11-18">2026</date>
<id>lirelle-test-notes-fb2</id>
<version>1.0</version>
</document-info>
</description>
<body>
<section>
<title><p>The Barometer</p></title>
<p>The harbour master kept a ledger of every tide he had ever seen.</p>
<p>Nobody trusted the barometer<a l:href="#n1" type="note">[1]</a>, least of all the man who owned it.</p>
<p>The tide tables were printed in a town far inland<a l:href="#n2" type="note">[2]</a>, and it showed.</p>
</section>
<section>
<title><p>The Morning Boat</p></title>
<p>Whatever the answer was, it would have to wait for the morning boat.</p>
</section>
</body>
<body name="notes">
<title><p>Notes</p></title>
<section id="n1">
<title><p>1</p></title>
<p>It had been dropped twice, once into the harbour.</p>
</section>
<section id="n2">
<title><p>2</p></title>
<p>The printer had never seen the sea.</p>
</section>
</body>
</FictionBook>
"""


def convert(name, options):
    target = OUT / name
    subprocess.run(["ebook-convert", str(SOURCE), str(target), *options], check=True, stdout=subprocess.DEVNULL)
    return target


def protect(mobi):
    data = bytearray(mobi.read_bytes())
    (first,) = struct.unpack_from(">I", data, RECORD_LIST)
    struct.pack_into(">H", data, first + ENCRYPTION, 2)
    target = OUT / "protected.mobi"
    target.write_bytes(data)
    return target


def cut(book, name, length):
    target = OUT / name
    target.write_bytes(book.read_bytes()[:length])
    return target


def main():
    if not shutil.which("ebook-convert"):
        sys.exit("ebook-convert (Calibre) is needed")
    if not SOURCE.exists():
        sys.exit("books/out/basic.epub is missing: run build.py first")
    built = [convert(name, options) for name, options in CONVERSIONS.items()]
    mobi, _, fb2 = built
    built.append(protect(mobi))
    built.append(cut(mobi, "damaged.mobi", DAMAGED_MOBI_BYTES))
    built.append(cut(fb2, "damaged.fb2", fb2.stat().st_size // 2))
    notes = OUT / "notes.fb2"
    notes.write_text(NOTES_FB2, encoding="utf-8")
    built.append(notes)
    for target in built:
        print(target.relative_to(OUT.parent))


if __name__ == "__main__":
    main()
