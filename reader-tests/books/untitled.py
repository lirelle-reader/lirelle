#!/usr/bin/env python3
"""Writes a FictionBook whose sections have no title, as Calibre makes one by default.

  untitled.fb2  a section holding a picture alone, then sections that start with their text:
                a long paragraph, a short one, and a long one written over several lines

The engine names such a section after its first paragraph, and gives no name to the one
that has none. It needs nothing but Python, and writes the same bytes each time; its
output is committed with the other books.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"

# A picture of one pixel, in base64 as a FictionBook holds it.
PIXEL = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
)

BOOK = f"""<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
<title-info>
<genre>prose</genre>
<author><first-name>Lirelle</first-name><last-name>Tests</last-name></author>
<book-title>The Unnamed Rooms</book-title>
<lang>en</lang>
</title-info>
<document-info>
<author><nickname>lirelle</nickname></author>
<date value="2026-11-18">2026</date>
<id>lirelle-test-untitled-fb2</id>
<version>1.0</version>
</document-info>
</description>
<body>
<section>
<image l:href="#plate.png"/>
</section>
<section>
<p>The harbour master kept a ledger of every tide he had ever seen, and of every boat that had come in on it, year after year.</p>
<p>Nobody trusted the barometer, its owner least of all.</p>
</section>
<section>
<p>A short room.</p>
<p>Whatever the answer was, it would have to wait for the morning boat.</p>
</section>
<section>
<p>The lamp   was lit at dusk,
    and it stayed lit until the last of the boats had come round the point and tied up below the wall.</p>
<p>Then the keeper went down to his supper.</p>
</section>
</body>
<binary id="plate.png" content-type="image/png">{PIXEL}</binary>
</FictionBook>
"""


def main():
    target = OUT / "untitled.fb2"
    target.write_bytes(BOOK.encode("utf-8"))
    print(target.relative_to(OUT.parent))


if __name__ == "__main__":
    main()
