package roo.display;

import hexwriter.HexWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Date;
import java.util.List;
import roo.display.RooDisplayFont.Glyph;
import roo.display.encode.*;
import roo.display.encode.alpha4.*;

class FontEncoder {
  boolean rle;
  final RooDisplayFont font;

  public FontEncoder(RooDisplayFont font) { this.font = font; }

  public RooDisplayFont getFont() { return font; }

  public void writeDeclaration(Writer os, String var) throws IOException {
    HexWriter hexWriter = new HexWriter(os);
    hexWriter.printComment("Font " + font.getFont().getPSName() + " (" +
                           font.getFont().getName() + ")\n");
    os.write("const Font& " + var + "();");
  }

  static class EncodedGlyph {
    public EncodedGlyph(byte[] data, boolean compressed) {
      this.data = data;
      this.compressed = compressed;
    }

    byte[] data;
    boolean compressed;
  };

  public int writeDefinition(Writer os, String var, boolean rle)
      throws IOException {
    final RooDisplayFont.MaxFontSize maxFontSize;

    // Use a StringWriter buffer to generate content with placeholders first.
    StringWriter buffer = new StringWriter();
    HexWriter hexWriter = new HexWriter(buffer);
    GlyphEncoder glyphEncoder = new GlyphEncoder(font.getAlphaBits(), rle);
    List<Glyph> glyphs = font.getGlyphs();

    // Actually encode all glyphs. We need this to know the sizes in advance, to
    // generate offsets.
    EncodedGlyph[] encodedGlyphs = new EncodedGlyph[glyphs.size()];
    for (int i = 0; i < glyphs.size(); ++i) {
      encodedGlyphs[i] = glyphEncoder.encodeGlyph(glyphs.get(i));
    }

    // Determine the maximum offset into the glyph array space.
    int maxOffset = 0;
    if (glyphs.size() > 0) {
      for (int i = 0; i < glyphs.size() - 1; ++i) {
        maxOffset += encodedGlyphs[i].data.length;
      }
    }
    final int offsetBytes = (maxOffset < (1 << 8))    ? 1
                            : (maxOffset < (1 << 16)) ? 2
                                                      : 3;
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
      maxRightOverhang =
          Math.max(maxRightOverhang,
                   glyph.getAdvance() - glyph.getBoundingBox().xMax + 1);
      if (glyph.getCodePoint() == 'i') {
        defaultSpaceAdvance = glyph.getAdvance();
      }
    }

    boolean fixedPoint =
        (minAdvance == maxAdvance && font.getKerningPairs().isEmpty());
    // int linesep = (int) Math.round(0.35 * (font.getAscent() +
    // font.getDescent())).
    int linesep = Math.max(maxBoundingBox.getHeight() -
                               (font.getAscent() - font.getDescent()),
                           (int)(0.2 * (font.getAscent() + font.getDescent())));

    maxFontMetricBytes =
        Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.xMin * 2));
    maxFontMetricBytes =
        Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.xMax));
    maxFontMetricBytes =
        Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.yMin));
    maxFontMetricBytes =
        Math.max(maxFontMetricBytes, signedBytes(maxBoundingBox.yMax));
    maxFontMetricBytes = Math.max(maxFontMetricBytes, signedBytes(maxAdvance));

    if (defaultSpaceAdvance == 0) {
      // Non-Roman font; fall back to something potentially reasonable.
      defaultSpaceAdvance = font.getAscent() / 2;
    }

    FontMetricWriter metricWriter =
        new FontMetricWriter(maxFontMetricBytes, hexWriter);
    OffsetWriter offsetWriter = new OffsetWriter(offsetBytes, hexWriter);

    hexWriter.printComment("Font " + font.getFont().getPSName() + " (" +
                           font.getFont().getName() + ")\n");
    hexWriter.printComment("Generated on " + new Date() + ".\n");
    hexWriter.printComment("@glyphCount@ glyphs, @totalBytes@ bytes total.\n");
    hexWriter.beginStatic(var);
    hexWriter.newLine();
    hexWriter.printComment("Header (@headerBytes@ bytes).");
    hexWriter.newLine();

    // Mark header start for byte counting.
    int headerStartBytes = hexWriter.getBytesWritten();

    hexWriter.printHex16(0x0102);
    hexWriter.printHex8(font.getAlphaBits().bits());
    hexWriter.printHex8(font.getCharset() == RooDisplayFont.Charset.ASCII ? 1
                                                                          : 2);
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

    hexWriter.printComment(
        "Glyph metrics (@glyphCount@ glyphs, @glyphMetricsBytes@ bytes).");

    // Mark glyph metrics start for byte counting.
    int glyphMetricsStartBytes = hexWriter.getBytesWritten();

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
      // Encode the information whether the glyph is RLE-compressed in the
      // xMin field's MSB (after the sign bit). xMin tends to be closest to
      // zero, so this bit is least likely used.
      //
      // If the sign bit is equal to that high bit, it means that the glyph is
      // compressed. if they are different, it means that the glyph is
      // uncompressed.
      //
      // In practice, we have not seen a font which would have this bit ever be
      // different than the sign bit so far. This means that this change is
      // backwards-compatible with existing font files.
      int val = boundingBox.xMin;
      if (!encodedGlyphs[i].compressed) {
        // Need to flip the bit.
        val ^= (1 << (8 * maxFontMetricBytes - 2));
      }
      metricWriter.print(val);
      metricWriter.print(boundingBox.yMin);
      metricWriter.print(boundingBox.xMax);
      metricWriter.print(boundingBox.yMax);
      metricWriter.print(glyph.getAdvance());
      offsetWriter.print(currentOffset);

      String comment = ("\"" + (char)glyph.getCodePoint() + "\"");
      comment += String.format(" (U+%04X)", glyph.getCodePoint());
      // Encode in UTF-8, because why not. It's just a comment.
      hexWriter.printComment(comment);
      currentOffset += encodedGlyphs[i].data.length;
    }

    hexWriter.newLine();
    hexWriter.newLine();
    hexWriter.printComment(
        "Kerning pairs (@kerningPairCount@ pairs, @kerningBytes@ bytes).");

    // Mark kerning pairs start for byte counting.
    int kerningStartBytes = hexWriter.getBytesWritten();

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
      hexWriter.printComment(
          "" + (char)cp.left + (char)cp.right +
          String.format(" (U+%04X U+%04X)", cp.left, cp.right));
    }

    hexWriter.newLine();
    hexWriter.newLine();
    hexWriter.printComment("Glyph data (@glyphDataBytes@ bytes).");

    // Mark glyph data start for byte counting.
    int glyphDataStartBytes = hexWriter.getBytesWritten();

    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      hexWriter.newLine();
      String comment = ("\"" + (char)glyph.getCodePoint() + "\"");
      comment += String.format(" (U+%04X)", glyph.getCodePoint());
      hexWriter.printComment(comment);
      hexWriter.newLine();
      hexWriter.printBuffer(encodedGlyphs[i].data);
    }

    // Add final statistics comment with placeholder.
    hexWriter.newLine();
    hexWriter.printComment("Total: @totalBytes@ bytes.");

    hexWriter.end();

    // Now calculate actual byte counts for each section.
    int headerBytes = glyphMetricsStartBytes - headerStartBytes;
    int glyphMetricsBytes = kerningStartBytes - glyphMetricsStartBytes;
    int kerningBytes = glyphDataStartBytes - kerningStartBytes;
    int glyphDataBytes = hexWriter.getBytesWritten() - glyphDataStartBytes;
    int totalBytes = hexWriter.getBytesWritten();

    // Get the generated content as a string with placeholders.
    String content = buffer.toString();

    // Replace all placeholders with actual values.
    content = content.replace("@glyphCount@", String.valueOf(glyphs.size()));
    content = content.replace("@totalBytes@", String.valueOf(totalBytes));
    content = content.replace("@headerBytes@", String.valueOf(headerBytes));
    content = content.replace("@glyphMetricsBytes@",
                              String.valueOf(glyphMetricsBytes));
    content = content.replace("@kerningPairCount@",
                              String.valueOf(font.getKerningPairs().size()));
    content = content.replace("@kerningBytes@", String.valueOf(kerningBytes));
    content =
        content.replace("@glyphDataBytes@", String.valueOf(glyphDataBytes));

    // Write the final content with substituted values to the actual output.
    os.write(content);

    return totalBytes;
  }

  public static class GlyphEncoder {
    private final RooDisplayFont.AlphaBits alphaBits;
    private final boolean rle;

    private static Encoder createEncoder(RooDisplayFont.AlphaBits alphaBits,
                                         boolean rle, OutputStream os) {
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

    public EncodedGlyph encodeGlyph(RooDisplayFont.Glyph glyph) {
      int width = glyph.getBoundingBox().getWidth();
      int height = glyph.getBoundingBox().getHeight();
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      // Always try non-RLE to see if it produces a smaller result.
      Encoder encoder = createEncoder(alphaBits, false, os);
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
      byte[] result = os.toByteArray();
      boolean compressed = false;
      if (rle) {
        os = new ByteArrayOutputStream();
        encoder = createEncoder(alphaBits, rle, os);
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
        byte[] rleResult = os.toByteArray();
        if (rleResult.length < result.length) {
          result = rleResult;
          compressed = true;
        }
      }
      return new EncodedGlyph(result, compressed);
    }
  }

  private static class FontMetricWriter {
    private final int fontMetricBytes;
    private final HexWriter writer;

    public FontMetricWriter(int fontMetricBytes, HexWriter writer) {
      this.fontMetricBytes = fontMetricBytes;
      this.writer = writer;
    }

    final int size() { return fontMetricBytes; }

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
