# Bundled reading fonts

The reader ships three font families, each under the SIL Open Font License, Version 1.1.
The full text of the licence is at the end of this file and applies to each of them.

## Literata — served as `LirelleSerif-*.woff2`

- Copyright 2017 The Literata Project Authors (https://github.com/googlefonts/literata)
- Source: https://github.com/googlefonts/literata, commit `0c2761b727a1b3a7cffd313c37f0f5163dfc7a63`
  (version 3.103), files `fonts/ttf/Literata-Regular.ttf`, `Literata-Italic.ttf`, `Literata-Bold.ttf`
- Licence: SIL Open Font License, Version 1.1. No Reserved Font Name.
- Modified: reduced to the characters listed under "Subsets" below, hinting removed, compressed as WOFF2.

## Atkinson Hyperlegible — served as `LirelleSans-*.woff2`

- Copyright 2020 Braille Institute of America, Inc.
- Source: https://github.com/googlefonts/atkinson-hyperlegible, commit
  `1cb311624b2ddf88e9e37873999d165a8cd28b46` (version 1.006), files
  `fonts/ttf/AtkinsonHyperlegible-Regular.ttf`, `AtkinsonHyperlegible-Italic.ttf`, `AtkinsonHyperlegible-Bold.ttf`
- Licence: SIL Open Font License, Version 1.1. No Reserved Font Name.
- Modified: reduced to the characters listed under "Subsets" below, hinting removed, compressed as WOFF2.

## OpenDyslexic — served as `LirelleDyslexic-*.woff2`

- Copyright (c) 2019-07-29, Abbie Gonzalez (https://abbiecod.es|support@abbiecod.es),
  with Reserved Font Name OpenDyslexic. Copyright (c) 12/2012 - 2019
- Source: https://github.com/antijingoist/opendyslexic, commit `1824da5c0e41dc3e13ffc7f3a636dcaf695d61b7`,
  files `compiled/OpenDyslexic-Regular.woff2`, `OpenDyslexic-Italic.woff2`, `OpenDyslexic-Bold.woff2`
- Licence: SIL Open Font License, Version 1.1.
- Not modified: these are upstream's own WOFF2 files, byte for byte; only the file names differ. They are
  deliberately not subset, since a modified version may not keep the reserved name.

## Subsets

Literata and Atkinson Hyperlegible keep, where the family has them: Basic Latin (U+0020–007E),
Latin-1 Supplement (U+00A0–00FF), Latin Extended-A (U+0100–017F), the spacing accents U+02C6, U+02C7 and
U+02D8–02DD, General Punctuation (U+2000–206F), Currency Symbols (U+20A0–20CF), U+2122 and U+2212, with
every OpenType layout feature and the whole name table (copyright and licence fields included).

Made with fontTools:

    python3 -m fontTools.subset <file>.ttf \
      --unicodes="U+0020-007E,U+00A0-00FF,U+0100-017F,U+02C6,U+02C7,U+02D8-02DD,U+2000-206F,U+20A0-20CF,U+2122,U+2212" \
      --layout-features='*' --name-IDs='*' --name-languages='*' --no-hinting --flavor=woff2

## SIL Open Font License, Version 1.1

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
