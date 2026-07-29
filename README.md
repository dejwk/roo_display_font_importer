# roo_display_font_importer
Tool for importing fonts for use with the roo_display library, in microcontroller UIs.

The resulting files can be directly compiled into your sketch.

## Example usage

Generate a few sizes of a given font, extracting a default character set:

```
./import_fonts -font NotoSans-Regular -sizes 9,10,12,15
```

Sizes may be fractional, for example `-sizes 12.5`. Generated filenames and
C++ symbols replace the decimal point with an underscore (`12_5.h` and
`font_NotoSans_Regular_12_5`).

Extract just digits, '-', and '.', and write output to a specified dir:

```
./import_fonts -font NotoSans-Regular -sizes 100 -charset 2D-2E,30-39 --output-dir=<dir>
```

List all available fonts:

```
./import_fonts -list
```

See all options:
```
./import_fonts -help
```
