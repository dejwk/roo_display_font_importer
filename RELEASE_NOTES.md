# [roo_display_font_importer 2.0](https://github.com/dejwk/roo_display_font_importer/releases/tag/2.0)

Published 2026-08-07.

New features
* new font format, V2, using significantly less space (~30%)
* added support for fractional font sizes
* added small numeric subscripts (1-5) to the default glyph list, for common chemical names (H₂O, NO₃, etc.)
* added support for uncompressed glyphs in case when compression doesn't save anything.
* added information on byte counts and compression in generated source comments

Bug fixes
* improved kerning accuracy

---

# [1.0](https://github.com/dejwk/roo_display_font_importer/releases/tag/1.0)

Published 2022-03-07.

# roo_display_font_importer
Tool for importing fonts for use with the roo_display library, in microcontroller UIs.

The resulting files can be directly compiled into your sketch.

## Example usage

Generate a few sizes of a given font, extracting a default character set:

```
roo_display_font_importer -font NotoSans-Regular -sizes 9,10,12,15
```

Extract just digits, '-', and '.', and write output to a specified dir:

```
roo_display_font_importer -font NotoSans-Regular -sizes 100 -charset 2D-2E,30-39 --output-dir=<dir>
```

List all available fonts:

```
roo_display_font_importer -list
```

See all options:
```
roo_display_font_importer -help
```


---

