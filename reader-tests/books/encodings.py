#!/usr/bin/env python3
"""Writes the FictionBooks that are not in UTF-8, as older tools made them.

  windows-1251.fb2  a Russian text in the one-byte encoding its XML declaration names
  utf-16.fb2        the same text in UTF-16, little-endian, behind a byte order mark

It needs nothing but Python, and writes the same bytes each time; its output
is committed with the other books.
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"

# A FictionBook as its own tools write one; `{encoding}` is what its declaration says.
BOOK = """<?xml version="1.0" encoding="{encoding}"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description>
<title-info>
<genre>prose</genre>
<author><first-name>Орбита</first-name><last-name>Тесты</last-name></author>
<book-title>Таблицы приливов</book-title>
<lang>ru</lang>
</title-info>
<document-info>
<author><nickname>lirelle</nickname></author>
<date value="2026-11-18">2026</date>
<id>lirelle-test-{encoding}-fb2</id>
<version>1.0</version>
</document-info>
</description>
<body>
<section>
<title><p>Барометр</p></title>
<p>Начальник порта вёл журнал всех приливов, которые он когда-либо видел.</p>
<p>Барометру никто не доверял, и меньше всех его владелец.</p>
</section>
<section>
<title><p>Утренний катер</p></title>
<p>Каким бы ни был ответ, ему придётся подождать утреннего катера.</p>
</section>
</body>
</FictionBook>
"""

# The file, the encoding its declaration names, and the one Python writes it in (`utf-16` puts the mark first).
BOOKS = [
    ("windows-1251.fb2", "windows-1251", "cp1251"),
    ("utf-16.fb2", "UTF-16", "utf-16"),
]


def main():
    for name, declared, codec in BOOKS:
        target = OUT / name
        target.write_bytes(BOOK.format(encoding=declared).encode(codec))
        print(target.relative_to(OUT.parent))


if __name__ == "__main__":
    main()
