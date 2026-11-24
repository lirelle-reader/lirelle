#!/usr/bin/env python3
"""Writes the sources of the test books under books/src/.

Everything here was written for this project: the sentences are invented, the
images are flat colours and the font is a set of rectangles. Run it only to
change the books; its output is committed. It needs fontTools for the `fonts`
book. `build.py` then zips each folder into books/out/<name>.epub.
"""
import hashlib
import shutil
import struct
import zlib
from pathlib import Path

SRC = Path(__file__).resolve().parent / "src"

EN = [
    "The harbour master kept a ledger of every tide he had ever seen.",
    "Nobody in Port Vesper remembered who had painted the lighthouse green.",
    "Marta counted the gulls on the breakwater and came up one short.",
    "A kettle whistled somewhere behind the net loft, thin and patient.",
    "The ferry was late again, which surprised no one and annoyed everyone.",
    "She folded the letter twice and slid it under the brass weight.",
    "Rain came in sideways from the point, smelling of kelp and diesel.",
    "Old Anselm swore the fog had a colour, though he could never name it.",
    "The lamp turned once every nine seconds, as it had for sixty years.",
    "There were three keys on the hook and only two doors they fitted.",
    "By noon the market had sold out of everything except turnips.",
    "The boy from the chandlery ran errands for anyone who paid in toffee.",
    "A rope creaked against the piling with the rhythm of slow breathing.",
    "Nobody trusted the barometer, least of all the man who owned it.",
    "The tide tables were printed in a town far inland, and it showed.",
    "She wrote the date at the top of the page and then stopped to think.",
    "Lanterns were lit along the quay long before the light had gone.",
    "The dog belonged to the whole street and answered to four names.",
    "Somewhere a shutter banged twice, then settled against its latch.",
    "He measured the afternoon in pipes smoked rather than in hours.",
    "The stairs of the tower numbered one hundred and twelve, not counting the ladder.",
    "A coil of wire, a tin of grease and a spare wick sat on the sill.",
    "Whatever the answer was, it would have to wait for the morning boat.",
    "The gulls returned at dusk, all of them this time, loud as a quarrel.",
    "Marta kept the window open a hand's width, whatever the weather.",
    "The sea that evening was the grey of a much-washed shirt.",
    "Two fishermen argued gently about a net neither of them owned.",
    "The clock in the square ran seven minutes fast and was loved for it.",
    "She had promised a reply before the equinox and meant to keep her word.",
    "Salt had eaten the hinges, so the gate hung open all year round.",
]

FR = [
    "Le gardien notait chaque marée dans un cahier à couverture bleue.",
    "Personne à Port-Vesprée ne savait plus qui avait repeint le phare.",
    "Marthe compta les goélands sur la digue et il en manquait un.",
    "Une bouilloire sifflait quelque part derrière la remise aux filets.",
    "Le bac avait encore du retard, ce qui ne surprenait plus personne.",
    "Elle plia la lettre en deux et la glissa sous le presse-papiers.",
    "La pluie arrivait de biais, avec une odeur de varech et de gazole.",
    "Le vieil Anselme jurait que la brume avait une couleur bien à elle.",
    "La lanterne faisait un tour toutes les neuf secondes, depuis soixante ans.",
    "Il y avait trois clefs au crochet et seulement deux portes à ouvrir.",
    "À midi, le marché n'avait plus rien à vendre, sauf des navets.",
    "Une corde grinçait contre le pieu au rythme d'une respiration lente.",
    "Personne ne se fiait au baromètre, et surtout pas son propriétaire.",
    "Elle écrivit la date en haut de la page, puis s'arrêta pour réfléchir.",
    "On allumait les fanaux le long du quai bien avant la tombée du jour.",
    "Le chien appartenait à toute la rue et répondait à quatre noms.",
    "L'escalier de la tour comptait cent douze marches, sans compter l'échelle.",
    "La réponse, quelle qu'elle fût, attendrait le bateau du matin.",
    "La mer, ce soir-là, avait le gris d'une chemise trop souvent lavée.",
    "L'horloge de la place avançait de sept minutes et on l'aimait ainsi.",
]


class Picker:
    """A small deterministic generator, so the books never change by accident."""

    def __init__(self, seed):
        self.state = int(hashlib.sha1(seed.encode()).hexdigest()[:8], 16)

    def next(self, bound):
        self.state = (self.state * 1103515245 + 12345) % (2 ** 31)
        return self.state % bound


def paragraphs(seed, count, pool=EN, sentences=5):
    picker = Picker(seed)
    out = []
    for _ in range(count):
        out.append(" ".join(pool[picker.next(len(pool))] for _ in range(sentences)))
    return out


def png(width, height, rgb):
    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    row = b"\x00" + bytes(rgb) * width
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height, 9))
            + chunk(b"IEND", b""))


def xhtml(title, body, lang="en", head="", html_attrs=""):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="{lang}" xml:lang="{lang}"{html_attrs}>
<head>
<meta charset="utf-8"/>
<title>{title}</title>
{head}</head>
<body>
{body}
</body>
</html>
"""


def paras(items):
    return "\n".join(f"<p>{text}</p>" for text in items)


CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>
"""


def opf(identifier, title, manifest, spine, lang="en", spine_attrs="", extra_meta=""):
    title_el = f"<dc:title>{title}</dc:title>\n" if title else ""
    items = "\n".join(
        f'<item id="{i}" href="{href}" media-type="{mt}"{(" properties=" + chr(34) + props + chr(34)) if props else ""}/>'
        for i, href, mt, props in manifest)
    refs = "\n".join(
        f'<itemref idref="{i}"{"" if linear else " linear=" + chr(34) + "no" + chr(34)}/>'
        for i, linear in spine)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" xml:lang="{lang}">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="pub-id">{identifier}</dc:identifier>
{title_el}<dc:language>{lang}</dc:language>
<dc:creator>Lirelle test books</dc:creator>
<meta property="dcterms:modified">2026-11-14T00:00:00Z</meta>
{extra_meta}</metadata>
<manifest>
{items}
</manifest>
<spine{spine_attrs}>
{refs}
</spine>
</package>
"""


def nav(title, list_html, lang="en"):
    body = f'<nav epub:type="toc" id="toc">\n<h1>{title}</h1>\n{list_html}\n</nav>'
    return xhtml(title, body, lang)


XHTML = "application/xhtml+xml"


def write(book, files):
    root = SRC / book
    if root.exists():
        shutil.rmtree(root)
    for name, data in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, str):
            path.write_text(data, encoding="utf-8", newline="\n")
        else:
            path.write_bytes(data)


def epub_files(opf_text, oebps, encryption=None):
    files = {"mimetype": "application/epub+zip", "META-INF/container.xml": CONTAINER,
             "OEBPS/content.opf": opf_text}
    if encryption:
        files["META-INF/encryption.xml"] = encryption
    for name, data in oebps.items():
        files["OEBPS/" + name] = data
    return files


# ------------------------------------------------------------------ basic

def basic():
    css = """body { margin: 0; }
h1 { color: #224488; }
p { margin: 0 0 0.8em 0; }
p.center { text-align: center; }
p.plate { text-align: center; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'
    ch1 = xhtml("The Green Lighthouse", f"""<h1 id="top">The Green Lighthouse</h1>
<p id="links">See <a id="internal" href="ch3.xhtml#reply">the reply</a> or the <a id="external" href="https://example.org/tides">tide tables</a>.</p>
<p class="center" id="centred">* * *</p>
{paras(paragraphs("basic-1a", 7))}
<h2 id="lamp">The lamp room</h2>
{paras(paragraphs("basic-1b", 7))}""", head=link)
    ch2 = xhtml("The Missing Gull", f"""<h1 id="top">The Missing Gull</h1>
<p class="plate"><img id="plate" src="images/tide.png" alt="A flat red plate" width="160" height="100"/></p>
{paras(paragraphs("basic-2", 12))}""", head=link)
    ch3 = xhtml("The Morning Boat", f"""<h1 id="top">The Morning Boat</h1>
{paras(paragraphs("basic-3a", 8))}
<h2 id="reply">The reply</h2>
{paras(paragraphs("basic-3b", 6))}
<p id="last">And that was the last entry in the ledger.</p>""", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml">The Green Lighthouse</a>
<ol><li><a href="ch1.xhtml#lamp">The lamp room</a></li></ol></li>
<li><a href="ch2.xhtml">The Missing Gull</a></li>
<li><a href="ch3.xhtml">The Morning Boat</a>
<ol><li><a href="ch3.xhtml#reply">The reply</a></li></ol></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("cover", "images/cover.png", "image/png", "cover-image"),
        ("tide", "images/tide.png", "image/png", ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
        ("ch3", "ch3.xhtml", XHTML, ""),
    ]
    write("basic", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000001", "The Ledger of Port Vesper", manifest,
            [("ch1", True), ("ch2", True), ("ch3", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css,
         "images/cover.png": png(120, 180, (34, 68, 136)),
         "images/tide.png": png(160, 100, (200, 30, 40)),
         "ch1.xhtml": ch1, "ch2.xhtml": ch2, "ch3.xhtml": ch3}))


# ------------------------------------------------------------------ rtl

def rtl():
    attrs = ' dir="rtl"'
    ch1 = xhtml("Le phare vert", f'<h1>Le phare vert</h1>\n{paras(paragraphs("rtl-1", 9, FR))}',
                "fr", html_attrs=attrs)
    ch2 = xhtml("Le bateau du matin", f'<h1>Le bateau du matin</h1>\n{paras(paragraphs("rtl-2", 9, FR))}',
                "fr", html_attrs=attrs)
    toc = """<ol>
<li><a href="ch1.xhtml">Le phare vert</a></li>
<li><a href="ch2.xhtml">Le bateau du matin</a></li>
</ol>"""
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("ch1", "ch1.xhtml", XHTML, ""),
                ("ch2", "ch2.xhtml", XHTML, "")]
    write("rtl", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000002", "Le registre de Port-Vesprée", manifest,
            [("ch1", True), ("ch2", True)], "fr", ' page-progression-direction="rtl"'),
        {"nav.xhtml": nav("Sommaire", toc, "fr"), "ch1.xhtml": ch1, "ch2.xhtml": ch2}))


# ------------------------------------------------------------------ fixed

def fixed():
    def page(number, colour, text):
        head = """<meta name="viewport" content="width=600, height=800"/>
<style>
html, body { margin: 0; width: 600px; height: 800px; }
body { background: %s; position: relative; }
p { position: absolute; left: 60px; top: 340px; width: 480px; margin: 0;
    font: 36px sans-serif; text-align: center; }
</style>
""" % colour
        return xhtml(f"Plate {number}", f'<p id="caption">{text}</p>', head=head)

    toc = """<ol>
<li><a href="page1.xhtml">First plate</a></li>
<li><a href="page2.xhtml">Second plate</a></li>
</ol>"""
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("p1", "page1.xhtml", XHTML, ""),
                ("p2", "page2.xhtml", XHTML, "")]
    meta = '<meta property="rendition:layout">pre-paginated</meta>\n'
    write("fixed", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000003", "Two Plates of the Harbour", manifest,
            [("p1", True), ("p2", True)], extra_meta=meta),
        {"nav.xhtml": nav("Contents", toc),
         "page1.xhtml": page(1, "#f4e9d0", "The quay at low water"),
         "page2.xhtml": page(2, "#d0e4f4", "The quay at high water")}))


# ------------------------------------------------------------------ footnote

def footnote():
    # Every kind of reference the note panel meets: one the book marks as such
    # (`epub:type="noteref"`), one only set in superscript, a note in the same
    # file, a note with a picture, a link inside a note, a target that does not
    # exist (on both paths), a "note" that is a whole chapter (on both paths),
    # a long note, notes that hold their own reference (with and without an
    # id), a link to the web inside a note, a target outside the reading order,
    # and notes with a link back to their reference: marked as such (by type or
    # by role), or only a number pointing back, as a word processor exports it,
    # to the reference or to a wrapper around it; links back that must stay (one
    # around the note's whole text, one to a wrapper holding more text); and
    # notes whose target is an empty anchor (found by superscript, and marked).
    first = paragraphs("note-1", 4)
    ch1 = xhtml("The Barometer", f"""<h1>The Barometer</h1>
<p id="claim">Nobody trusted the barometer<a id="ref1" epub:type="noteref" href="notes.xhtml#n1">1</a>, and the harbour got on well enough without it.</p>
<p id="refs">The ledger<sup><a id="ref2" href="notes.xhtml#n2">2</a></sup> named the lamp<a id="ref3" epub:type="noteref" href="#n3">3</a>, the chart<a id="ref4" epub:type="noteref" href="notes.xhtml#n4">4</a> and the tide<a id="ref5" epub:type="noteref" href="notes.xhtml#n5">5</a>; it forgot a key<a id="ref6" epub:type="noteref" href="notes.xhtml#gone">6</a> and a rope<sup><a id="ref7" href="notes.xhtml#nowhere">7</a></sup>, told the long story<sup><a id="ref8" href="notes.xhtml#s8">8</a></sup> and the longer one<a id="ref9" epub:type="noteref" href="notes.xhtml#n9">9</a>.</p>
<p id="loop">The tide table cites itself<sup><a id="ref10" href="#loop">10</a></sup>, and so does <span id="circle">the almanac<sup><a href="#circle">11</a></sup></span>; the whole story<a id="ref12" epub:type="noteref" href="notes.xhtml#story">12</a> is elsewhere, and the chart alone<a id="ref13" epub:type="noteref" href="images/chart.png">13</a> too.</p>
<p id="back">The net<a id="ref14" epub:type="noteref" href="notes.xhtml#n14">14</a>, the oar<sup><a id="ref15" href="notes.xhtml#n15">[15]</a></sup> and the bell<a id="ref16" epub:type="noteref" href="notes.xhtml#n16">16</a> came back; the anchor<sup><a id="ref17" href="notes.xhtml#a17">17</a></sup>, the rope<a id="ref18" epub:type="noteref" href="notes.xhtml#n18">18</a>, <span id="w19">the gate<sup><a id="ref19" href="notes.xhtml#n19">19</a></sup></span> and <span id="w20"><sup><a id="ref20" href="notes.xhtml#n20">20</a></sup></span> did not, nor the press<a id="ref21" epub:type="noteref" href="notes.xhtml#a21">21</a>.</p>
{paras(first)}
<aside epub:type="footnote" id="n3"><p>3. The lamp was trimmed at dusk by whoever was nearest.</p></aside>""")
    long_note = "\n".join(f"<p>{text}</p>" for text in paragraphs("note-9", 4))
    story = paras(paragraphs("note-story", 16))
    notes = xhtml("Notes", f"""<h1>Notes</h1>
<aside epub:type="footnote" id="n1"><p>1. It had been dropped twice, once into the harbour.</p></aside>
<p id="n2">2. The ledger was bound in sailcloth and smelled of tar.</p>
<aside epub:type="footnote" id="n4"><p>4. The chart is reproduced here: <img id="chart" src="images/chart.png" alt="A flat green chart" width="120" height="60"/></p></aside>
<aside epub:type="footnote" id="n5"><p>5. See <a id="inner" href="notes.xhtml#n2">the second note</a> for the ledger, or <a id="web" href="https://example.org/tides">the tide tables</a>.</p></aside>
<aside epub:type="footnote" id="n14"><p><a id="back14" epub:type="backlink" href="ch1.xhtml#ref14">14.</a> The net was mended with green twine.</p></aside>
<p id="n15"><a id="back15" href="ch1.xhtml#ref15">[15]</a> The oar had a name painted on its blade.</p>
<aside epub:type="footnote" id="n16"><p>16. The bell was cast for another harbour. <a id="back16" role="doc-backlink" href="ch1.xhtml#ref16">Back</a></p></aside>
<p id="p17"><a id="a17"></a>17. The anchor was left empty by the printer.</p>
<aside epub:type="footnote" id="n18"><p><a id="back18" epub:type="backlink" href="ch1.xhtml#ref18">18. The rope was tarred twice a year.</a></p></aside>
<p id="n19"><a id="back19" href="ch1.xhtml#w19">^</a> 19. The gate hung open all year round.</p>
<p id="n20"><a id="back20" href="ch1.xhtml#w20">20</a> The twentieth note points back to a wrapper.</p>
<p id="p21"><a id="a21"></a>21. The press left this anchor empty as well.</p>
<aside epub:type="footnote" id="n9"><p>9. The longer story, as the harbour master told it.</p>
{long_note}
</aside>
<div id="story"><span id="s8">8.</span>
{story}
</div>""")
    toc = '<ol>\n<li><a href="ch1.xhtml">The Barometer</a></li>\n</ol>'
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("chart", "images/chart.png", "image/png", ""),
                ("ch1", "ch1.xhtml", XHTML, ""), ("notes", "notes.xhtml", XHTML, "")]
    write("footnote", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000004", "A Note on Barometers", manifest,
            [("ch1", True), ("notes", False)]),
        {"nav.xhtml": nav("Contents", toc), "images/chart.png": png(120, 60, (20, 160, 60)),
         "ch1.xhtml": ch1, "notes.xhtml": notes}))


# ------------------------------------------------------------------ scripted

def scripted():
    # Each of these would leave a trace if it ran: a title, a flag on the
    # reader's own window, a request to another host.
    inline = """<script>
document.title = 'SCRIPT RAN inline';
try { window.top.document.title = 'SCRIPT RAN inline'; window.top.lirelleScriptRan = 'inline'; } catch (e) {}
try { fetch('https://evil.example/inline'); } catch (e) {}
</script>
"""
    script_file = """document.title = 'SCRIPT RAN file';
try { window.top.document.title = 'SCRIPT RAN file'; window.top.lirelleScriptRan = 'file'; } catch (e) {}
new Image().src = 'https://evil.example/file.png';
"""
    handler = ("document.title = 'SCRIPT RAN onclick'; "
               "try { window.top.document.title = 'SCRIPT RAN onclick'; "
               "window.top.lirelleScriptRan = 'onclick'; } catch (e) {} "
               "new Image().src = 'https://evil.example/onclick.png';")
    head = inline + '<script src="script.js"></script>\n'
    ch1 = xhtml("A Quiet Chapter", f"""<h1>A Quiet Chapter</h1>
<p id="trap" onclick="{handler}">Touch this sentence and nothing at all should happen.</p>
<p><img id="beacon" src="https://evil.example/pixel.png" alt="A picture from elsewhere"/></p>
<p><a id="jslink" href="javascript:void(window.top.document.title='SCRIPT RAN link')">A link that is really a script.</a></p>
<p id="noted">The notes try as well<a id="noteref" epub:type="noteref" href="notes.xhtml#trapnote">1</a>.</p>
{paras(paragraphs("scripted-1", 5))}""", head=head)
    # The note panel shows this file in a second view: the same traps again.
    note_handler = ("document.title = 'SCRIPT RAN note'; "
                    "try { window.top.document.title = 'SCRIPT RAN note'; "
                    "window.top.lirelleScriptRan = 'note'; } catch (e) {} "
                    "new Image().src = 'https://evil.example/note-onclick.png';")
    notes = xhtml("Notes That Tried", f"""<h1>Notes</h1>
<aside epub:type="footnote" id="trapnote"><p id="notetrap" onclick="{note_handler}">1. Touch this note and nothing should happen.</p>
<p><img id="notebeacon" src="https://evil.example/note.png" alt="Another picture from elsewhere"/></p></aside>""",
                  head=head)
    toc = '<ol>\n<li><a href="ch1.xhtml">A Quiet Chapter</a></li>\n</ol>'
    manifest = [("nav", "nav.xhtml", XHTML, "nav"),
                ("ch1", "ch1.xhtml", XHTML, "scripted"),
                ("notes", "notes.xhtml", XHTML, "scripted"),
                ("js", "script.js", "application/javascript", "")]
    write("scripted", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000005", "The Book That Tried", manifest,
            [("ch1", True), ("notes", False)]),
        {"nav.xhtml": nav("Contents", toc), "ch1.xhtml": ch1, "notes.xhtml": notes, "script.js": script_file}))


# ------------------------------------------------------------------ hinted

def hinted():
    # Resource hints: each asks the browser to look up, connect to or fetch
    # from a host of the book's choosing, before anything is displayed.
    hints = ["dns-prefetch", "preconnect", "prefetch", "prerender", "preload", "modulepreload",
             "PRECONNECT", "stylesheet preload"]
    head = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'
    head += '<meta http-equiv="x-dns-prefetch-control" content="on"/>\n'
    head += "".join(f'<link rel="{rel}" href="https://evil.example/{index}"/>\n'
                    for index, rel in enumerate(hints))
    ch1 = xhtml("A Chapter With Hints", f"""<h1>A Chapter With Hints</h1>
<p><a id="away" href="https://evil.example/away">A link to somewhere else.</a></p>
<link rel="preconnect" href="https://evil.example/in-body"/>
<meta http-equiv="x-dns-prefetch-control" content="on"/>
<p id="after">A paragraph after hints that sit in the body.</p>
{paras(paragraphs("hinted-1", 5))}""", head=head)
    # One more between the head and the body, a child of the root itself.
    ch1 = ch1.replace("</head>\n<body>", '</head>\n<link rel="prefetch" href="https://evil.example/root"/>\n<body>')
    toc = '<ol>\n<li><a href="ch1.xhtml">A Chapter With Hints</a></li>\n</ol>'
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("css", "style.css", "text/css", ""),
                ("ch1", "ch1.xhtml", XHTML, "")]
    write("hinted", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000009", "The Book That Hinted", manifest,
            [("ch1", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": "h1 { color: #224488; }\n", "ch1.xhtml": ch1}))


# ------------------------------------------------------------------ fonts

def rectangle_font():
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.ttGlyphPen import TTGlyphPen

    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    names = [".notdef", "space"] + [f"g{ord(c):04X}" for c in letters]

    def box(width, height):
        pen = TTGlyphPen(None)
        pen.moveTo((60, 0))
        pen.lineTo((60, height))
        pen.lineTo((width - 60, height))
        pen.lineTo((width - 60, 0))
        pen.closePath()
        return pen.glyph()

    glyphs = {".notdef": box(500, 700), "space": TTGlyphPen(None).glyph()}
    metrics = {".notdef": (500, 60), "space": (300, 0)}
    for i, c in enumerate(letters):
        width = 420 + 20 * (i % 9)
        glyphs[f"g{ord(c):04X}"] = box(width, 700 if c.isupper() else 480)
        metrics[f"g{ord(c):04X}"] = (width, 60)

    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder(names)
    fb.setupCharacterMap({32: "space", **{ord(c): f"g{ord(c):04X}" for c in letters}})
    fb.setupGlyf(glyphs)
    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    fb.setupNameTable({"familyName": "Lirelle Pebble", "styleName": "Regular"})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost()
    fb.setupHead(unitsPerEm=1000, created=0, modified=0)
    fb.font.recalcTimestamp = False
    from io import BytesIO
    out = BytesIO()
    fb.font.save(out)
    return out.getvalue()


def idpf_obfuscate(data, identifier):
    key = hashlib.sha1("".join(identifier.split()).encode("utf-8")).digest()
    head = bytes(b ^ key[i % len(key)] for i, b in enumerate(data[:1040]))
    return head + data[1040:]


def fonts():
    identifier = "urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000006"
    css = """@font-face { font-family: "Lirelle Pebble"; src: url("fonts/Pebble.ttf"); }
body { font-family: "Lirelle Pebble", serif; }
"""
    ch1 = xhtml("Pebbles", f'<h1 id="heading">Pebbles</h1>\n{paras(paragraphs("fonts-1", 4))}',
                head='<link rel="stylesheet" type="text/css" href="style.css"/>\n')
    toc = '<ol>\n<li><a href="ch1.xhtml">Pebbles</a></li>\n</ol>'
    encryption = """<?xml version="1.0" encoding="utf-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData>
<enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
<enc:CipherData><enc:CipherReference URI="OEBPS/fonts/Pebble.ttf"/></enc:CipherData>
</enc:EncryptedData>
</encryption>
"""
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("css", "style.css", "text/css", ""),
                ("font", "fonts/Pebble.ttf", "font/ttf", ""), ("ch1", "ch1.xhtml", XHTML, "")]
    write("fonts", epub_files(
        opf(identifier, "A Book Set in Pebbles", manifest, [("ch1", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css, "ch1.xhtml": ch1,
         "fonts/Pebble.ttf": idpf_obfuscate(rectangle_font(), identifier)},
        encryption))


# ------------------------------------------------------------------ protected

def protected():
    # The chapter is noise, as an encrypted entry would be.
    noise = b"".join(hashlib.sha256(f"lirelle-{i}".encode()).digest() for i in range(64))
    toc = '<ol>\n<li><a href="ch1.xhtml">A Locked Chapter</a></li>\n</ol>'
    encryption = """<?xml version="1.0" encoding="utf-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
<enc:EncryptedData>
<enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
<enc:CipherData><enc:CipherReference URI="OEBPS/ch1.xhtml"/></enc:CipherData>
</enc:EncryptedData>
</encryption>
"""
    manifest = [("nav", "nav.xhtml", XHTML, "nav"), ("ch1", "ch1.xhtml", XHTML, "")]
    write("protected", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000007", "The Locked Ledger", manifest,
            [("ch1", True)]),
        {"nav.xhtml": nav("Contents", toc), "ch1.xhtml": noise}, encryption))


# ------------------------------------------------------------------ overflowing

def overflowing():
    # What a word processor's export does to a page: margins that pull a
    # paragraph out of its column, widths set in pixels, a table too wide for
    # a phone and words that cannot be broken. The legitimate indents next to
    # them must survive.
    css = """body { margin: 0; }
p { margin: 0 0 0.6em 0; }
p.MsoNormal { margin: 0 0 0.6em 0; }
p.centred { text-align: center; margin-right: -48px; }
p.pulled { margin-left: -30px; }
p.indented { margin-left: 48px; }
p.hanging { padding-left: 2em; text-indent: -2em; }
p.outdented { text-indent: -3em; }
div.box { padding: 0 40px; }
div.box p { margin-left: -20px; margin-right: -20px; }
div.wide { width: 900px; padding: 4px; border: 1px solid #888888; }
div.floor { min-width: 700px; }
p.single { white-space: nowrap; }
table { border-collapse: collapse; }
td, th { border: 1px solid #888888; padding: 2px 4px; }
div.cover { text-align: center; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'
    cover = xhtml("Cover", '<div class="cover"><img id="cover" src="images/cover.png" alt="A flat green cover"/></div>',
                  head=link)

    def classed(seed, count):
        kinds = ["MsoNormal", "MsoNormal centred", "MsoNormal indented", "MsoNormal pulled", "MsoNormal hanging"]
        return "\n".join(f'<p class="{kinds[i % len(kinds)]}">{text}</p>'
                         for i, text in enumerate(paragraphs(seed, count, sentences=3)))

    first = paragraphs("overflowing-1", 7, sentences=3)
    ch1 = xhtml("The Margins of the Ledger", f"""<h1 id="top">The Margins of the Ledger</h1>
<p class="MsoNormal" id="plain">{first[0]}</p>
<p class="MsoNormal centred" id="centred">{first[1]}</p>
<p class="MsoNormal pulled" id="pulled">{first[2]}</p>
<p class="MsoNormal indented" id="indented">{first[3]}</p>
<p class="MsoNormal hanging" id="hanging">{first[4]}</p>
<p class="MsoNormal outdented" id="outdented">{first[5]}</p>
<div class="box" id="box"><p id="boxed">{first[6]}</p></div>
{classed("overflowing-1b", 20)}""", head=link)

    address = "https://port-vesper.example/ledgers/" + "/".join(
        f"tide-{i:03d}-of-the-harbour-master" for i in range(8)) + "?page=112&sort=by-the-colour-of-the-fog"
    word = "Lighthousekeeperstidetablekeeping" * 6
    heads = ["Day", "Tide", "Gulls", "Lanterns", "Weather", "Ferry", "Catch", "Remarks"]
    rows = [
        ["Monday", "Highest", "Eleven", "Fourteen", "Sideways", "Late", "Mackerel", "Nothing"],
        ["Tuesday", "Middling", "Twelve", "Fourteen", "Foggy", "Later", "Turnips", "Quarrels"],
        ["Wednesday", "Lowest", "Twelve", "Thirteen", "Clearing", "Absent", "Herring", "Toffee"],
    ]
    table = ('<table id="table">\n<tr>' + "".join(f"<th>{h}</th>" for h in heads) + "</tr>\n"
             + "\n".join("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>" for row in rows)
             + "\n</table>")
    wide = paragraphs("overflowing-2", 12, sentences=3)
    ch2 = xhtml("Things Too Wide", f"""<h1 id="top">Things Too Wide</h1>
<p id="address">The tables were kept at {address} for a while.</p>
<p id="word">{word}</p>
{table}
<div class="wide" id="wide">{wide[0]}</div>
<p class="single" id="single">{wide[1]}</p>
<pre id="pre">{wide[2]}</pre>
<div class="floor" id="floor">{wide[3]}</div>
{paras(wide[4:])}""", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml">The Margins of the Ledger</a></li>
<li><a href="ch2.xhtml">Things Too Wide</a></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("cover-image", "images/cover.png", "image/png", "cover-image"),
        ("cover", "cover.xhtml", XHTML, ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
    ]
    write("overflowing", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000010", "The Ledger That Ran Off the Page", manifest,
            [("cover", True), ("ch1", True), ("ch2", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css,
         "images/cover.png": png(120, 180, (30, 110, 70)),
         "cover.xhtml": cover, "ch1.xhtml": ch1, "ch2.xhtml": ch2}))


# ------------------------------------------------------------------ marks

def marks():
    # Highlights and bookmarks: chapters long enough for several pages, a
    # paragraph with inline markup, a title page outside the reading order and
    # outside the contents, and a chapter missing from the contents.
    css = """body { margin: 0; }
p { margin: 0 0 0.8em 0; }
span.aside { font-variant: small-caps; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'

    def marked(seed):
        s = paragraphs(seed, 1, sentences=1)[0], *paragraphs(seed + "-more", 4, sentences=1)
        return (f'{s[0]} <em>{s[1]}</em> {s[2]} <strong>{s[3]}</strong> '
                f'<span class="aside">{s[4]}</span>')

    title = xhtml("Title", '<h1 id="title">The Keeper of the Ledger</h1>', head=link)
    first = paragraphs("marks-1", 28)
    ch1 = xhtml("The Tide Ledger", f"""<h1 id="c1">The Tide Ledger</h1>
<p id="plain">{first[0]}</p>
<p id="inline">{marked("marks-inline")}</p>
<p id="third">{first[1]}</p>
{paras(first[2:16])}
<h2 id="margin">A Note in the Margin</h2>
{paras(first[16:])}""", head=link)
    ch2 = xhtml("An Unlisted Chapter", f"""<h1 id="c2">An Unlisted Chapter</h1>
<p id="opening">{marked("marks-2")}</p>
{paras(paragraphs("marks-2", 9))}""", head=link)
    ch3 = xhtml("The Last Lantern", f"""<h1 id="c3">The Last Lantern</h1>
<p id="first">{marked("marks-3")}</p>
{paras(paragraphs("marks-3", 8))}""", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml#c1">The Tide Ledger</a>
<ol><li><a href="ch1.xhtml#margin">A Note in the Margin</a></li></ol></li>
<li><a href="ch3.xhtml">The Last Lantern</a></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("title", "title.xhtml", XHTML, ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
        ("ch3", "ch3.xhtml", XHTML, ""),
    ]
    write("marks", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000011", "The Keeper of the Ledger", manifest,
            [("title", False), ("ch1", True), ("ch2", True), ("ch3", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css, "title.xhtml": title,
         "ch1.xhtml": ch1, "ch2.xhtml": ch2, "ch3.xhtml": ch3}))


# ------------------------------------------------------------------ selection

def selection():
    # Text selection: a picture with no text around it, and a chapter whose text
    # runs well past the 10 000 characters a selection message carries.
    css = """body { margin: 0; }
p { margin: 0 0 0.8em 0; }
figure { margin: 0 0 0.8em 0; text-align: center; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'
    opening = paragraphs("selection-1", 2)
    ch1 = xhtml("The Long Ledger", f"""<h1 id="c1">The Long Ledger</h1>
<p id="first">{opening[0]}</p>
<figure id="figure"><img id="plate" src="images/plate.png" alt="" width="120" height="60"/></figure>
<p id="second">{opening[1]}</p>
{paras(paragraphs("selection-2", 70))}""", head=link)
    ch2 = xhtml("The Short Ledger", f"""<h1 id="c2">The Short Ledger</h1>
{paras(paragraphs("selection-3", 6))}""", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml">The Long Ledger</a></li>
<li><a href="ch2.xhtml">The Short Ledger</a></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("plate", "images/plate.png", "image/png", ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
    ]
    write("selection", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000012", "The Long Ledger", manifest,
            [("ch1", True), ("ch2", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css,
         "images/plate.png": png(120, 60, (40, 120, 90)),
         "ch1.xhtml": ch1, "ch2.xhtml": ch2}))


# ------------------------------------------------------------------ accents

LONG_TARGET = 300_000
LONG_PARAGRAPH = (
    "Le registre des marées tenait sur une étagère, près de la fenêtre de la tour. Chaque soir, le gardien y "
    "notait la hauteur de l'eau, la force du vent et la couleur de la brume. Les pages jaunies sentaient le sel "
    "et la cire. Personne ne les relisait jamais, mais personne n'aurait osé les jeter : on disait au port que "
    "le registre comptait davantage que le phare lui-même.")


def accents():
    # Search: French text with the three spellings of one word, a word cut in two by inline markup, a title page
    # outside the contents, a chapter missing from the contents, and one long chapter of about 300 000 characters
    # (one invented paragraph, repeated) to time a search on.
    css = """body { margin: 0; }
p { margin: 0 0 0.8em 0; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'
    title = xhtml("Titre", '<h1 id="title">La brume de Port-Vesprée</h1>', "fr", head=link)
    ch1 = xhtml("Le cahier du matin", f"""<h1 id="c1">Le cahier du matin</h1>
<p id="fog">Ce matin-là, la brume cachait encore la digue et le bout du quai.</p>
<p id="accented">Chaque matin, l'élève recopiait les marées du jour dans un carnet.</p>
<p id="capital">Élève appliqué, il soulignait les grandes marées en rouge.</p>
<p id="plain">Le maître appelait cela le carnet de l'eleve, sans accent, par habitude.</p>
<p id="split">Le pilote tenait le gouver<em>nail</em> d'une seule main.</p>
{paras(paragraphs("accents-1", 14, FR))}
<p id="late">Le soir venu, la brume revint sur le port.</p>""", "fr", head=link)
    ch2 = xhtml("Le quai", f"""<h1 id="c2">Le quai</h1>
{paras(paragraphs("accents-2", 3, FR))}""", "fr", head=link)
    repeats = -(-LONG_TARGET // (len(LONG_PARAGRAPH) + 1))
    ch3 = xhtml("Le long registre", f"""<h1 id="c3">Le long registre</h1>
{paras([LONG_PARAGRAPH] * repeats)}""", "fr", head=link)
    ch4 = xhtml("Post-scriptum", """<h1 id="c4">Post-scriptum</h1>
<p id="after">Une dernière ligne, écrite dans la brume du soir.</p>""", "fr", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml">Le cahier du matin</a></li>
<li><a href="ch2.xhtml">Le quai</a></li>
<li><a href="ch3.xhtml">Le long registre</a></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("title", "title.xhtml", XHTML, ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
        ("ch3", "ch3.xhtml", XHTML, ""),
        ("ch4", "ch4.xhtml", XHTML, ""),
    ]
    write("accents", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000013", "Le registre des marées", manifest,
            [("title", False), ("ch1", True), ("ch2", True), ("ch3", True), ("ch4", True)], "fr"),
        {"nav.xhtml": nav("Sommaire", toc, "fr"), "style.css": css, "title.xhtml": title,
         "ch1.xhtml": ch1, "ch2.xhtml": ch2, "ch3.xhtml": ch3, "ch4.xhtml": ch4}))


# ------------------------------------------------------------------ leaves

LEAVES = 300
LEAVES_PER_BUNDLE = 50


def leaves():
    # Search over many short sections, each holding the word "brume" once: the results of a whole-book search
    # must not come one message per section.
    files = {}
    manifest = [("nav", "nav.xhtml", XHTML, "nav")]
    spine = []
    entries = []
    for n in range(1, LEAVES + 1):
        name = f"f{n:03}.xhtml"
        files[name] = xhtml(f"Feuillet {n}", f"""<h1>Feuillet {n}</h1>
<p>Sur le feuillet {n}, la brume effaçait déjà le nom du bateau.</p>""", "fr")
        manifest.append((f"f{n:03}", name, XHTML, ""))
        spine.append((f"f{n:03}", True))
        if n % LEAVES_PER_BUNDLE == 1:
            entries.append(f'<li><a href="{name}">Liasse {n // LEAVES_PER_BUNDLE + 1}</a></li>')
    files["nav.xhtml"] = nav("Sommaire", "<ol>\n" + "\n".join(entries) + "\n</ol>", "fr")
    write("leaves", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000014", "Trois cents feuillets", manifest, spine, "fr"),
        files))


# ------------------------------------------------------------------ readalong

def readalong():
    # Read-along: sentences in spans with ids of the form s<section>-s<sentence>, as a book with a narration has
    # them, headings with no id, and in the first chapter an interlude of several pages that is not narrated, so
    # that the narrated sentences after it, and the second chapter, are on later pages.
    css = """body { margin: 0; }
p { margin: 0 0 0.8em 0; }
"""
    link = '<link rel="stylesheet" type="text/css" href="style.css"/>\n'

    def narrated(section, first, count, seed):
        picker = Picker(seed)
        spans = []
        for n in range(first, first + count):
            spans.append(f'<span id="s{section}-s{n}">{EN[picker.next(len(EN))]}</span>')
        return " ".join(spans)

    ch1 = xhtml("The Tide Clock", f"""<h1>The Tide Clock</h1>
<p id="p1">{narrated(1, 1, 6, "readalong-1")}</p>
<p id="p2">{narrated(1, 7, 6, "readalong-2")}</p>
<p id="p3">{narrated(1, 13, 6, "readalong-3")}</p>
<div id="interlude">
{paras(paragraphs("readalong-interlude", 12))}
</div>
<p id="p4">{narrated(1, 19, 6, "readalong-4")}</p>
<p id="p5">{narrated(1, 25, 6, "readalong-5")}</p>""", head=link)
    ch2 = xhtml("The Second Tide", f"""<h1>The Second Tide</h1>
<p id="q1">{narrated(2, 1, 6, "readalong-6")}</p>
<p id="q2">{narrated(2, 7, 6, "readalong-7")}</p>""", head=link)
    toc = """<ol>
<li><a href="ch1.xhtml">The Tide Clock</a></li>
<li><a href="ch2.xhtml">The Second Tide</a></li>
</ol>"""
    manifest = [
        ("nav", "nav.xhtml", XHTML, "nav"),
        ("css", "style.css", "text/css", ""),
        ("ch1", "ch1.xhtml", XHTML, ""),
        ("ch2", "ch2.xhtml", XHTML, ""),
    ]
    write("readalong", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000015", "The Tide Clock", manifest,
            [("ch1", True), ("ch2", True)]),
        {"nav.xhtml": nav("Contents", toc), "style.css": css, "ch1.xhtml": ch1, "ch2.xhtml": ch2}))


# ------------------------------------------------------------------ bare, broken

def bare():
    ch1 = xhtml("", f'{paras(paragraphs("bare-1", 6))}')
    ch2 = xhtml("", f'{paras(paragraphs("bare-2", 6))}')
    manifest = [("ch1", "ch1.xhtml", XHTML, ""), ("ch2", "ch2.xhtml", XHTML, "")]
    write("bare", epub_files(
        opf("urn:uuid:0b1f7c2e-51a4-4c0e-9a57-000000000008", "", manifest,
            [("ch1", True), ("ch2", True)]),
        {"ch1.xhtml": ch1, "ch2.xhtml": ch2}))


def broken():
    write("broken", {"NOT_A_ZIP": "This file is not a zip archive, whatever its name says.\n"})


if __name__ == "__main__":
    for make in (basic, rtl, fixed, footnote, scripted, hinted, fonts, protected, overflowing, marks, selection,
                 accents, leaves, readalong, bare, broken):
        make()
    print(f"Wrote the sources under {SRC}")
