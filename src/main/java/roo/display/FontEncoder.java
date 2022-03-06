package roo.display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Date;
import java.util.List;

import hexwriter.HexWriter;
import roo.display.RooDisplayFont.Glyph;
import roo.display.encode.*;
import roo.display.encode.alpha4.*;

class FontEncoder {
  boolean rle;
  final RooDisplayFont font;

  public FontEncoder(RooDisplayFont font) {
    this.font = font;
  }

  public RooDisplayFont getFont() {
    return font;
  }

  public void writeDeclaration(Writer os, String var) throws IOException {
    HexWriter hexWriter = new HexWriter(os);
    hexWriter.printComment("Font " + font.getFont().getPSName() + " (" + font.getFont().getName() + ")\n");
    os.write("const Font& " + var + "();");
  }

  public int writeDefinition(Writer os, String var, boolean rle) throws IOException {
    final RooDisplayFont.MaxFontSize maxFontSize;
    HexWriter hexWriter = new HexWriter(os);
    GlyphEncoder glyphEncoder = new GlyphEncoder(font.getAlphaBits(), rle);
    List<Glyph> glyphs = font.getGlyphs();

    // Actually encode all glyphs. We need this to know the sizes in advance, to
    // generate offsets.
    byte[][] encodedGlyphs = new byte[glyphs.size()][];
    for (int i = 0; i < glyphs.size(); ++i) {
      encodedGlyphs[i] = glyphEncoder.encodeGlyph(glyphs.get(i));
    }

    // Determine the maximum offset into the glyph array space.
    int maxOffset = 0;
    if (glyphs.size() > 0) {
      for (int i = 0; i < glyphs.size() - 1; ++i) {
        maxOffset += encodedGlyphs[i].length;
      }
    }
    final int offsetBytes = (maxOffset < (1 << 8)) ? 1 : (maxOffset < (1 << 16)) ? 2 : 3;
    // OffsetWriter offsetWriter = new OffsetWriter(offsetBytes, hexWriter);

    int maxFontMetricBytes = 1;
    int defaultSpaceAdvance = 0;
    int minAdvance = Integer.MAX_VALUE;
    int maxAdvance = 0;
    int maxRightOverhang = 0;
    RooDisplayFont.BoundingBox maxBoundingBox = null;
    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      maxBoundingBox = glyph.getBoundingBox().expand(maxBoundingBox);
      minAdvance = Math.min(minAdvance, glyph.getAdvance());
      maxAdvance = Math.max(maxAdvance, glyph.getAdvance());
      maxRightOverhang = Math.max(
          maxRightOverhang,
          glyph.getAdvance() - glyph.getBoundingBox().xMax + 1);
      if (glyph.getCodePoint() == 'i') {
        defaultSpaceAdvance = glyph.getAdvance();
      }
    }

    boolean fixedPoint = (minAdvance == maxAdvance && font.getKerningPairs().isEmpty());
    // int linesep = (int) Math.round(0.35 * (font.getAscent() +
    // font.getDescent()));
    int linesep = Math.max(
        maxBoundingBox.getHeight() - (font.getAscent() - font.getDescent()),
        (int) (0.2 * (font.getAscent() + font.getDescent())));

    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.xMin));
    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.xMax));
    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.yMin));
    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.yMax));
    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxAdvance));

    if (defaultSpaceAdvance == 0) {
      // Non-Roman font; fall back to something potentially reasonable.
      defaultSpaceAdvance = font.getAscent() / 2;
    }

    FontMetricWriter metricWriter = new FontMetricWriter(maxFontMetricBytes, hexWriter);
    OffsetWriter offsetWriter = new OffsetWriter(offsetBytes, hexWriter);

    hexWriter.printComment("Font " + font.getFont().getPSName() + " (" + font.getFont().getName() + ")\n");
    hexWriter.printComment("Generated on " + new Date() + "\n");
    hexWriter.beginStatic(var);
    hexWriter.newLine();
    hexWriter.printComment("Header");
    hexWriter.newLine();
    hexWriter.printHex16(0x0101);
    hexWriter.printHex8(font.getAlphaBits().bits());
    hexWriter.printHex8(font.getCharset() == RooDisplayFont.Charset.ASCII ? 1 : 2);
    hexWriter.printHex8(maxFontMetricBytes);
    hexWriter.printHex8(offsetBytes);
    hexWriter.printHex8(rle ? 0x01 : 0x00);
    hexWriter.printHex16(glyphs.size());
    hexWriter.printHex16(font.getKerningPairs().size());

    hexWriter.newLine();
    metricWriter.print(maxBoundingBox.xMin);
    metricWriter.print(maxBoundingBox.yMin);
    metricWriter.print(maxBoundingBox.xMax);
    metricWriter.print(maxBoundingBox.yMax);
    metricWriter.print(font.getAscent());
    metricWriter.print(font.getDescent());
    metricWriter.print(linesep);
    metricWriter.print(minAdvance);
    metricWriter.print(maxAdvance);
    metricWriter.print(maxRightOverhang);
    metricWriter.print(defaultSpaceAdvance);

    // Default glyph to substitute if a requested glyph is missing.
    switch (font.getCharset()) {
      case ASCII: {
        hexWriter.printHex8('_');
        break;
      }
      case UTF8: {
        hexWriter.printHex16('_');
        break;
      }
    }

    hexWriter.newLine();
    hexWriter.newLine();

    hexWriter.printComment("Glyph metrics");
    int currentOffset = 0;
    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      hexWriter.newLine();
      switch (font.getCharset()) {
        case ASCII:
          hexWriter.printHex8(glyph.getCodePoint());
          break;
        case UTF8:
          hexWriter.printHex16(glyph.getCodePoint());
          break;
      }
      RooDisplayFont.BoundingBox boundingBox = glyph.getBoundingBox();
      metricWriter.print(boundingBox.xMin);
      metricWriter.print(boundingBox.yMin);
      metricWriter.print(boundingBox.xMax);
      metricWriter.print(boundingBox.yMax);
      metricWriter.print(glyph.getAdvance());
      offsetWriter.print(currentOffset);

      String comment = ("\"" + (char) glyph.getCodePoint() + "\"");
      comment += String.format(" (U+%04X)", glyph.getCodePoint());
      // Encode in UTF-8, because why not. It's just a comment.
      hexWriter.printComment(comment);
      currentOffset += encodedGlyphs[i].length;
    }

    hexWriter.newLine();
    hexWriter.newLine();
    hexWriter.printComment("Kerning pairs");
    for (RooDisplayFont.KerningPair i : font.getKerningPairs()) {
      RooDisplayFont.CodePointPair cp = i.codePoints;
      hexWriter.newLine();
      switch (font.getCharset()) {
        case ASCII:
          hexWriter.printHex8(cp.left);
          hexWriter.printHex8(cp.right);
          break;
        case UTF8:
          hexWriter.printHex16(cp.left);
          hexWriter.printHex16(cp.right);
          break;
      }
      if (i.kern < 1 || i.kern > 255) {
        throw new IllegalArgumentException("Kern outside range: " + i.kern);
      }
      hexWriter.printHex8(i.kern);
      hexWriter
          .printComment("" + (char) cp.left + (char) cp.right +
              String.format(" (U+%04X U+%04X)", cp.left, cp.right));
    }

    hexWriter.newLine();
    hexWriter.newLine();
    hexWriter.printComment("Glyph data");
    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      hexWriter.newLine();
      String comment = ("\"" + (char) glyph.getCodePoint() + "\"");
      comment += String.format(" (U+%04X)", glyph.getCodePoint());
      hexWriter.printComment(comment);
      hexWriter.newLine();
      hexWriter.printBuffer(encodedGlyphs[i]);
    }

    hexWriter.end();

    return hexWriter.getBytesWritten();
  }

  public static class GlyphEncoder {
    private final RooDisplayFont.AlphaBits alphaBits;
    private final boolean rle;

    private static Encoder createEncoder(RooDisplayFont.AlphaBits alphaBits, boolean rle, OutputStream os) {
      switch (alphaBits.bits()) {
        case 4:
          return new Alpha4EncoderFactory().create(rle, os);
        default:
          throw new UnsupportedOperationException();
      }
    }

    public GlyphEncoder(RooDisplayFont.AlphaBits alphaBits, boolean rle) {
      this.alphaBits = alphaBits;
      this.rle = rle;
    }

    public byte[] encodeGlyph(RooDisplayFont.Glyph glyph) {
      int width = glyph.getBoundingBox().getWidth();
      int height = glyph.getBoundingBox().getHeight();
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      Encoder encoder = createEncoder(alphaBits, rle, os);
      try {
        for (int rowid = 0; rowid < height; ++rowid) {
          for (int colid = 0; colid < width; ++colid) {
            encoder.encodePixel(glyph.getPixelColor(colid, rowid));
          }
        }
        encoder.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return os.toByteArray();
    }
  }

  private static class FontMetricWriter {
    private final int fontMetricBytes;
    private final HexWriter writer;

    public FontMetricWriter(int fontMetricBytes, HexWriter writer) {
      this.fontMetricBytes = fontMetricBytes;
      this.writer = writer;
    }

    final int size() {
      return fontMetricBytes;
    }

    void print(int metric) throws IOException {
      switch (fontMetricBytes) {
        case 1:
          writer.printHex8(metric & 0xFF);
          break;
        case 2:
          writer.printHex16(metric & 0xFFFF);
          break;
        case 3:
          writer.printHex24(metric & 0xFFFFFF);
          break;
        default:
          throw new AssertionError();
      }
    }
  }

  private static class OffsetWriter {
    private final int offsetBytes;
    private final HexWriter writer;

    public OffsetWriter(int offsetBytes, HexWriter writer) {
      this.offsetBytes = offsetBytes;
      this.writer = writer;
    }

    void print(int offset) throws IOException {
      switch (offsetBytes) {
        case 1: {
          writer.printHex8(offset);
          break;
        }
        case 2: {
          writer.printHex16(offset);
          break;
        }
        case 3: {
          writer.printHex24(offset);
          break;
        }
        default:
          throw new AssertionError();
      }
    }
  }

  private static int unsignedBytes(int unsignedValue) {
    if (unsignedValue < 0) {
      throw new IllegalArgumentException();
    }
    if (unsignedValue < (1 << 8))
      return 1;
    if (unsignedValue < (1 << 16))
      return 2;
    if (unsignedValue < (1 << 24))
      return 3;
    return 4;
  }

  private static int signedBytes(int value) {
    if (value < (1 << 7) && value >= -(1 << 7))
      return 1;
    if (value < (1 << 15) && value >= -(1 << 15))
      return 2;
    if (value < (1 << 24) && value >= -(1 << 24))
      return 3;
    return 4;
  }

}
