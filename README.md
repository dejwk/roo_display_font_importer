# roo_display_font_importer
Tool for importing fonts for use with the roo_display library, in microcontroller UIs.

The resulting files can be directly compiled into your sketch.

## Example usage

Generate a few sizes of a given font:

```
./import_fonts -font NotoSans-Regular -sizes 9,10,12,15
```

Generate just digits (and '-', '.'), and write to a specified dir:

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
