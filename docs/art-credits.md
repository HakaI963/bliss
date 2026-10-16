# Banner art credits

The Bliss terminal launch sticker (`folio-banner`) is rendered from
**truecolor half-block** versions of the anime-style illustrations below.
Each source was cropped to a head-and-shoulders composition, downscaled, and
quantized to `▀` (U+2580) half-block cells with per-cell foreground/background
RGB (see `scripts/tools/ansi_art_gen.py`). No filters change the artwork's
meaning; the derived ANSI art (files `01-anime-girl.txt` … `05-catgirl.txt`
under `~/.config/folio/banner/`) is redistributed under the **same license as
the source image**.

| Ship name | Character / motif | Source (Wikimedia Commons file page) | Author | License |
|---|---|---|---|---|
| `01-anime-girl` | Brown-haired girl with yellow ribbon (bust) | https://commons.wikimedia.org/wiki/File:Anime_girl_publicdomainq.png | oksmith (Open Clipart Library) | **CC0 1.0** (public domain dedication) — https://creativecommons.org/publicdomain/zero/1.0/ |
| `02-maid` | Purple twin-tailed maid with white headdress (face) | https://commons.wikimedia.org/wiki/File:Wikipe-tan_NovelAI.jpg | Peacearth (puzzle-piece design from “Wikipe-tan full length.svg” by Kasuga and Editor at Large) | **CC BY-SA 3.0** — https://creativecommons.org/licenses/by-sa/3.0/ |
| `03-kitsune` | Red-haired fox girl with fox ears (chibi portrait) | https://commons.wikimedia.org/wiki/File:Wikitsune.png | EmuAGR | **CC BY-SA 3.0** — https://creativecommons.org/licenses/by-sa/3.0/ |
| `04-chibi` | White cyber-squirrel chibi girl (Krita mascot, face) | https://commons.wikimedia.org/wiki/File:Kiki_the_Cyber_Squirrel_mascot_of_Krita_cropped_square_profile.png | Tyson Tan | **CC BY-SA 4.0** — https://creativecommons.org/licenses/by-sa/4.0/ |
| `05-catgirl` | Orange-haired cat-eared girl (bust) | https://commons.wikimedia.org/wiki/File:Anime_Girl_upright_version.jpg | Niabot | **CC BY-SA 3.0** — https://creativecommons.org/licenses/by-sa/3.0/ |

No copyrighted characters are used; all five sources are free-licensed
Wikimedia Commons files and each remains attributable above.

## Regenerating

`scripts/tools/ansi_art_gen.py` (dev-only, Python 3 stdlib + ffmpeg) performs
the whole pipeline for a variant:

```
python3 scripts/tools/ansi_art_gen.py pipeline \
    --in  <source image> \        # downloaded from the file page above
    --crop W:H:X:Y \              # head-and-shoulders crop in source pixels
    --w 43 --rows 19 \            # terminal columns x text rows (each row = 2 px)
    --colors 40 --gstep 6 \       # median-cut palette / channel stepping
    --alpha|--mode flood|key \    # background removal for the source kind
    --flat 6 --vignette ... --eq ... \
    --name 01-anime-girl \
    --out banner/01-anime-girl.txt --preview preview.png
```

The `.txt` output stores escapes as *literal* `\033[38;2;…;48;2;…m` text
(rendered by the guest with `printf '%b'`), one `▀`/space per cell, with a
`\033[0m` reset at the end of each line. `--preview` re-parses that exact text
back to RGB and writes a PNG for visual QA. Both files are byte-checked into
the six setup scripts (`FOLIO_BANNER_ALL` heredoc) and the Kotlin
`GuestZshrcRepair.BANNER_FILES` list.
