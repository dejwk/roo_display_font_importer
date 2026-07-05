package roo.display;

import hexwriter.HexWriter;
import hexwriter.PayloadWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import roo.display.RooDisplayFont.Glyph;
import roo.display.encode.*;
import roo.display.encode.alpha4.*;
import roo.display.imageimporter.CppPayloadSupport;
import roo.display.imageimporter.ImportOptions.CppPayloadFormat;

class FontEncoder {
  private static final int KERNING_FORMAT_NONE = 0;
  private static final int KERNING_FORMAT_PAIRS = 1;
  private static final int KERNING_FORMAT_CLASSES = 2;

  boolean rle;
  final RooDisplayFont font;
  private final String nameSuffix;

  public FontEncoder(RooDisplayFont font) { this(font, ""); }

  public FontEncoder(RooDisplayFont font, String nameSuffix) {
    this.font = font;
    this.nameSuffix = nameSuffix == null ? "" : nameSuffix;
  }

  public RooDisplayFont getFont() { return font; }

  private String getDisplayFontName() {
    return font.getFont().getPSName() + nameSuffix;
  }

  public void writeDeclaration(Writer os, String var) throws IOException {
    HexWriter hexWriter = new HexWriter(os);
    hexWriter.printComment("Font " + getDisplayFontName() + " (" +
                           font.getFont().getName() + ")\n");
    os.write("const Font& " + var + "();");
  }

  static class EncodedGlyph {
    public EncodedGlyph(byte[] data, boolean compressed, int uncompressedSize) {
      this.data = data;
      this.compressed = compressed;
      this.uncompressedSize = uncompressedSize;
    }

    byte[] data;
    boolean compressed;
    int uncompressedSize;

    public int getBytesSaved() {
      return compressed ? uncompressedSize - data.length : 0;
    }
  };

  static class KerningClassEntry {
    public final int destGlyphIndex;
    public final int kern;

    KerningClassEntry(int destGlyphIndex, int kern) {
      this.destGlyphIndex = destGlyphIndex;
      this.kern = kern;
    }
  }

  static class KerningSourceEntry {
    public final int sourceGlyphIndex;
    public final int classId;

    KerningSourceEntry(int sourceGlyphIndex, int classId) {
      this.sourceGlyphIndex = sourceGlyphIndex;
      this.classId = classId;
    }
  }

  static class KerningClasses {
    final int format;
    final int sourceCount;
    final int classCount;
    final int entryCount;
    final List<KerningSourceEntry> sources;
    final List<List<KerningClassEntry>> classes;

    KerningClasses(int format, int sourceCount, int classCount, int entryCount,
                   List<KerningSourceEntry> sources,
                   List<List<KerningClassEntry>> classes) {
      this.format = format;
      this.sourceCount = sourceCount;
      this.classCount = classCount;
      this.entryCount = entryCount;
      this.sources = sources;
      this.classes = classes;
    }
  }

  static class KerningPairEntry {
    public final int leftGlyphIndex;
    public final int rightGlyphIndex;
    public final int kern;
    public final int leftCodePoint;
    public final int rightCodePoint;

    KerningPairEntry(int leftGlyphIndex, int rightGlyphIndex, int kern,
                     int leftCodePoint, int rightCodePoint) {
      this.leftGlyphIndex = leftGlyphIndex;
      this.rightGlyphIndex = rightGlyphIndex;
      this.kern = kern;
      this.leftCodePoint = leftCodePoint;
      this.rightCodePoint = rightCodePoint;
    }
  }

  static class CmapEntry {
    int rangeStart;
    int rangeLength;
    int glyphIdOffset;
    int dataEntriesCount;
    long dataOffset;
    int format; // 0=dense, 1=sparse
    int[] indirection;
  }

  static class DenseRange {
    int startIndex;
    int endIndex;
    int startCp;
    int endCp;
  }

  private static List<DenseRange> findDenseRanges(List<Glyph> glyphs) {
    List<DenseRange> denseRanges = new ArrayList<>();
    // First pass: detect consecutive glyph runs. Runs of length >= 8 become
    // dense ranges.
    int i = 0;
    while (i < glyphs.size()) {
      int startIdx = i;
      int startCp = glyphs.get(i).getCodePoint();
      int j = i + 1;
      while (j < glyphs.size() && glyphs.get(j).getCodePoint() ==
                                      glyphs.get(j - 1).getCodePoint() + 1) {
        ++j;
      }
      int len = j - startIdx;
      if (len >= 8) {
        DenseRange range = new DenseRange();
        range.startIndex = startIdx;
        range.endIndex = j - 1;
        range.startCp = startCp;
        range.endCp = glyphs.get(j - 1).getCodePoint();
        denseRanges.add(range);
      }
      i = j;
    }
    return denseRanges;
  }

  private static CmapEntry buildDenseEntry(DenseRange range) {
    CmapEntry entry = new CmapEntry();
    entry.rangeStart = range.startCp;
    entry.rangeLength = range.endCp - range.startCp + 1;
    entry.glyphIdOffset = range.startIndex;
    entry.format = 0;
    entry.dataEntriesCount = 0;
    entry.indirection = null;
    return entry;
  }

  private static CmapEntry
  buildSparseEntry(List<Glyph> glyphs, int glyphStartIndex, int glyphEndIndex) {
    if (glyphStartIndex > glyphEndIndex)
      return null;
    int count = glyphEndIndex - glyphStartIndex + 1;
    int rangeStart = glyphs.get(glyphStartIndex).getCodePoint();
    int rangeEnd = glyphs.get(glyphEndIndex).getCodePoint();
    CmapEntry entry = new CmapEntry();
    entry.rangeStart = rangeStart;
    entry.rangeLength = rangeEnd - rangeStart + 1;
    entry.glyphIdOffset = glyphStartIndex;
    entry.format = 1;
    entry.dataEntriesCount = count;
    entry.indirection = new int[count];
    for (int i = 0; i < count; ++i) {
      entry.indirection[i] =
          glyphs.get(glyphStartIndex + i).getCodePoint() - rangeStart;
    }
    return entry;
  }

  private static List<CmapEntry> buildCmapEntries(List<Glyph> glyphs) {
    List<CmapEntry> entries = new ArrayList<>();
    if (glyphs.isEmpty())
      return entries;

    // Second pass: emit sparse ranges to cover gaps between dense ranges.
    // Sparse ranges are trimmed to the first/last glyph in their segment.
    List<DenseRange> denseRanges = findDenseRanges(glyphs);
    if (denseRanges.isEmpty()) {
      CmapEntry sparse = buildSparseEntry(glyphs, 0, glyphs.size() - 1);
      if (sparse != null)
        entries.add(sparse);
      return entries;
    }

    int glyphIndex = 0;
    int prevDenseEndCp = denseRanges.get(0).startCp - 1;
    for (int r = 0; r < denseRanges.size(); ++r) {
      DenseRange dense = denseRanges.get(r);
      int sparseStartCp =
          (r == 0) ? glyphs.get(0).getCodePoint() : prevDenseEndCp + 1;
      int sparseEndCp = dense.startCp - 1;
      if (sparseStartCp <= sparseEndCp) {
        int sparseGlyphStart = glyphIndex;
        while (glyphIndex < glyphs.size() &&
               glyphs.get(glyphIndex).getCodePoint() < dense.startCp) {
          ++glyphIndex;
        }
        int sparseGlyphEnd = glyphIndex - 1;
        CmapEntry sparse =
            buildSparseEntry(glyphs, sparseGlyphStart, sparseGlyphEnd);
        if (sparse != null)
          entries.add(sparse);
      }

      entries.add(buildDenseEntry(dense));
      glyphIndex = dense.endIndex + 1;
      prevDenseEndCp = dense.endCp;
    }

    DenseRange lastDense = denseRanges.get(denseRanges.size() - 1);
    int tailStartCp = lastDense.endCp + 1;
    int tailEndCp = glyphs.get(glyphs.size() - 1).getCodePoint();
    if (tailStartCp <= tailEndCp) {
      int sparseGlyphStart = lastDense.endIndex + 1;
      int sparseGlyphEnd = glyphs.size() - 1;
      CmapEntry sparse =
          buildSparseEntry(glyphs, sparseGlyphStart, sparseGlyphEnd);
      if (sparse != null)
        entries.add(sparse);
    }

    return entries;
  }

  private static void printHex32(PayloadWriter writer, long value)
      throws IOException {
    int hi = (int)((value >> 16) & 0xFFFF);
    int lo = (int)(value & 0xFFFF);
    writer.printHex16(hi);
    writer.printHex16(lo);
  }

  public int writeDefinition(Writer os, String var, boolean rle, CppPayloadFormat cppPayloadFormat)
      throws IOException {
    final RooDisplayFont.MaxFontSize maxFontSize;

    // Use a StringWriter buffer to generate content with placeholders first.
    StringWriter buffer = new StringWriter();
    PayloadWriter hexWriter = CppPayloadSupport.createPayloadWriter(buffer, cppPayloadFormat);
    GlyphEncoder glyphEncoder = new GlyphEncoder(font.getAlphaBits(), rle);
    List<Glyph> glyphs = font.getGlyphs();

    // Build a glyph index map for kerning (glyph index is the position in
    // glyph list).
    java.util.Map<Integer, Integer> codepointToGlyphIndex =
        new java.util.HashMap<>();
    for (int i = 0; i < glyphs.size(); ++i) {
      codepointToGlyphIndex.put(glyphs.get(i).getCodePoint(), i);
    }

    KerningClasses kerningClasses =
        encodeKerningClasses(font.getKerningPairs(), codepointToGlyphIndex);
    List<KerningPairEntry> kerningPairs =
        encodeKerningPairs(font.getKerningPairs(), codepointToGlyphIndex);

    // Actually encode all glyphs. We need this to know the sizes in advance, to
    // generate offsets.
    EncodedGlyph[] encodedGlyphs = new EncodedGlyph[glyphs.size()];
    int compressedGlyphCount = 0;
    int uncompressedGlyphCount = 0;
    int totalBytesSaved = 0;
    for (int i = 0; i < glyphs.size(); ++i) {
      encodedGlyphs[i] = glyphEncoder.encodeGlyph(glyphs.get(i));
      if (encodedGlyphs[i].compressed) {
        compressedGlyphCount++;
        totalBytesSaved += encodedGlyphs[i].getBytesSaved();
      } else {
        uncompressedGlyphCount++;
      }
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

    List<CmapEntry> cmapEntries = buildCmapEntries(glyphs);

    int encodingBytes =
        font.getCharset() == RooDisplayFont.Charset.ASCII ? 1 : 2;
    int glyphMetricsBytes =
        glyphs.size() * ((5 * maxFontMetricBytes) + offsetBytes);
    int cmapTableBytes = cmapEntries.size() * 12;
    int indirectionBytes = 0;
    for (CmapEntry entry : cmapEntries) {
      if (entry.format == 1 && entry.dataEntriesCount > 0) {
        indirectionBytes += entry.dataEntriesCount * 2;
      }
    }
    int cmapBytes = cmapTableBytes + indirectionBytes;

    int glyphIndexBytes = glyphs.size() < (1 << 8) ? 1 : 2;
    int kerningHeaderBytes;
    int kerningBytes;
    if (kerningClasses.format == KERNING_FORMAT_CLASSES) {
      int entrySize = glyphIndexBytes + 1;
      kerningHeaderBytes = 6;
      kerningBytes =
          kerningHeaderBytes + kerningClasses.sourceCount * entrySize +
          kerningClasses.classCount * 4 + kerningClasses.entryCount * entrySize;
    } else if (kerningClasses.format == KERNING_FORMAT_PAIRS) {
      kerningHeaderBytes = 2;
      kerningBytes =
          kerningHeaderBytes + kerningPairs.size() * (2 * glyphIndexBytes + 1);
    } else {
      kerningHeaderBytes = 0;
      kerningBytes = 0;
    }

    int headerBytes = 2 + 1 + 1 + 1 + 1 + 1 + 2 + 2 +
                      (11 * maxFontMetricBytes) + encodingBytes + 1 + 3 + 2 +
                      1 + 1;
    int glyphMetricsOffset = headerBytes + cmapBytes;
    int glyphDataOffset = glyphMetricsOffset + glyphMetricsBytes + kerningBytes;

    hexWriter.printComment("Font " + getDisplayFontName() + " (" +
                           font.getFont().getName() + ")\n");
    hexWriter.printComment("Generated on " + new Date() + ".\n");
    hexWriter.printComment("@glyphStats@\n");
    hexWriter.beginStatic(var, "@totalBytes@");
    hexWriter.newLine();
    hexWriter.printComment("Header (@headerStats@).");
    hexWriter.newLine();

    // Mark header start for byte counting.
    int headerStartBytes = hexWriter.getBytesWritten();

    hexWriter.printHex16(0x0200);
    hexWriter.printHex8(font.getAlphaBits().bits());
    hexWriter.printHex8(font.getCharset() == RooDisplayFont.Charset.ASCII ? 1
                                                                          : 2);
    hexWriter.printHex8(maxFontMetricBytes);
    hexWriter.printHex8(offsetBytes);
    hexWriter.printHex8(rle ? 0x01 : 0x00);
    hexWriter.printHex16(glyphs.size());
    hexWriter.printHex16(cmapEntries.size());

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

    // Kerning format (2 LSBs), reserved bits must be 0.
    hexWriter.newLine();
    hexWriter.printHex8(kerningClasses.format & 0x03);
    hexWriter.printHex16(glyphMetricsOffset);
    hexWriter.printHex24(glyphDataOffset);
    hexWriter.printHex8(0x00);
    hexWriter.printHex8(0x00);

    hexWriter.newLine();
    hexWriter.newLine();

    hexWriter.printComment("Cmap (@cmapStats@).");

    // Mark cmap start for byte counting.
    int cmapStartBytes = hexWriter.getBytesWritten();
    cmapTableBytes = cmapEntries.size() * 12;
    int indirectionOffset = cmapStartBytes + cmapTableBytes;
    int currentIndirectionOffset = 0;
    for (CmapEntry entry : cmapEntries) {
      if (entry.format == 1 && entry.dataEntriesCount > 0) {
        entry.dataOffset = indirectionOffset + currentIndirectionOffset;
        currentIndirectionOffset += entry.dataEntriesCount * 2;
      } else {
        entry.dataOffset = 0;
      }
    }

    for (CmapEntry entry : cmapEntries) {
      hexWriter.newLine();
      hexWriter.printHex16(entry.rangeStart);
      hexWriter.printHex16(entry.rangeLength);
      hexWriter.printHex16(entry.glyphIdOffset);
      hexWriter.printHex16(entry.dataEntriesCount);
      hexWriter.printHex24((int)entry.dataOffset);
      hexWriter.printHex8(entry.format);

      String comment = String.format("U+%04X..U+%04X", entry.rangeStart,
                                     entry.rangeStart + entry.rangeLength - 1);
      comment += (entry.format == 0) ? " dense." : " sparse.";
      hexWriter.printComment(comment);
    }

    boolean wroteSparseSection = false;
    for (CmapEntry entry : cmapEntries) {
      if (entry.format != 1 || entry.dataEntriesCount == 0) {
        continue;
      }
      int glyphStart = entry.glyphIdOffset;
      int glyphEnd = entry.glyphIdOffset + entry.dataEntriesCount - 1;
      int rangeEnd = entry.rangeStart + entry.rangeLength - 1;
      if (wroteSparseSection) {
        hexWriter.newLine();
      } else {
        hexWriter.newLine();
        hexWriter.newLine();
      }
      hexWriter.printComment(String.format(
          "Sparse indirection: glyphs %d..%d, range U+%04X..U+%04X.",
          glyphStart, glyphEnd, entry.rangeStart, rangeEnd));
      hexWriter.newLine();
      for (int i = 0; i < entry.indirection.length; ++i) {
        hexWriter.printHex16(entry.indirection[i]);
        int codePoint = entry.rangeStart + entry.indirection[i];
        hexWriter.printComment(String.format("U+%04X.", codePoint));
        hexWriter.newLine();
      }
      wroteSparseSection = true;
    }

    if (wroteSparseSection) {
      hexWriter.newLine();
    } else {
      hexWriter.newLine();
      hexWriter.newLine();
    }

    hexWriter.printComment("Glyph metrics (@glyphMetricsStats@).");

    // Mark glyph metrics start for byte counting.
    int glyphMetricsStartBytes = hexWriter.getBytesWritten();

    int currentOffset = 0;
    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      hexWriter.newLine();
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
    hexWriter.printComment("Kerning (@kerningDetails@).");
    hexWriter.newLine();

    // Mark kerning pairs start for byte counting.
    int kerningStartBytes = hexWriter.getBytesWritten();

    if (kerningClasses.format == KERNING_FORMAT_CLASSES) {
      hexWriter.newLine();
      hexWriter.printComment("Kerning header.");
      hexWriter.newLine();
      hexWriter.printHex16(kerningClasses.classCount);
      hexWriter.printHex16(kerningClasses.sourceCount);
      hexWriter.printHex16(kerningClasses.entryCount);

      hexWriter.newLine();
      hexWriter.newLine();
      hexWriter.printComment("Kerning sources.");
      // Sources: (glyph_index, class_id)
      for (KerningSourceEntry entry : kerningClasses.sources) {
        hexWriter.newLine();
        if (glyphIndexBytes == 1) {
          hexWriter.printHex8(entry.sourceGlyphIndex);
        } else {
          hexWriter.printHex16(entry.sourceGlyphIndex);
        }
        hexWriter.printHex8(entry.classId);
        int cp = glyphs.get(entry.sourceGlyphIndex).getCodePoint();
        hexWriter.printComment("\"" + (char)cp + "\" (" +
                               String.format("U+%04X", cp) + ") -> class " +
                               entry.classId);
      }

      hexWriter.newLine();
      hexWriter.newLine();
      hexWriter.printComment("Kerning classes.");
      // Classes: (offset, count)
      int entryOffset = 0;
      for (int classId = 0; classId < kerningClasses.classes.size();
           ++classId) {
        List<KerningClassEntry> entries = kerningClasses.classes.get(classId);
        hexWriter.newLine();
        hexWriter.printHex16(entryOffset);
        hexWriter.printHex16(entries.size());
        hexWriter.printComment("class " + classId + " entries");
        entryOffset += entries.size();
      }

      hexWriter.newLine();
      hexWriter.newLine();
      hexWriter.printComment("Kerning destinations.");
      // Class entries: (dest_glyph_index, weight)
      for (int classId = 0; classId < kerningClasses.classes.size();
           ++classId) {
        for (KerningClassEntry entry : kerningClasses.classes.get(classId)) {
          hexWriter.newLine();
          if (glyphIndexBytes == 1) {
            hexWriter.printHex8(entry.destGlyphIndex);
          } else {
            hexWriter.printHex16(entry.destGlyphIndex);
          }
          hexWriter.printHex8(entry.kern);
          int cp = glyphs.get(entry.destGlyphIndex).getCodePoint();
          hexWriter.printComment("class " + classId + " -> \"" + (char)cp +
                                 "\" (" + String.format("U+%04X", cp) + ")");
        }
      }
    } else if (kerningClasses.format == KERNING_FORMAT_PAIRS) {
      hexWriter.newLine();
      hexWriter.printComment("Kerning header.");
      hexWriter.newLine();
      hexWriter.printHex16(kerningPairs.size());

      hexWriter.newLine();
      hexWriter.newLine();
      hexWriter.printComment("Kerning destinations.");
      for (KerningPairEntry entry : kerningPairs) {
        hexWriter.newLine();
        if (glyphIndexBytes == 1) {
          hexWriter.printHex8(entry.leftGlyphIndex);
          hexWriter.printHex8(entry.rightGlyphIndex);
        } else {
          hexWriter.printHex16(entry.leftGlyphIndex);
          hexWriter.printHex16(entry.rightGlyphIndex);
        }
        hexWriter.printHex8(entry.kern);
        hexWriter.printComment("\"" + (char)entry.leftCodePoint + "\" (" +
                               String.format("U+%04X", entry.leftCodePoint) +
                               ") \"" + (char)entry.rightCodePoint + "\" (" +
                               String.format("U+%04X", entry.rightCodePoint) +
                               ")");
      }
    }

    hexWriter.newLine();
    hexWriter.newLine();
    hexWriter.printComment("Glyph data (@glyphDataStats@).");
    hexWriter.newLine();

    // Mark glyph data start for byte counting.
    int glyphDataStartBytes = hexWriter.getBytesWritten();

    for (int i = 0; i < glyphs.size(); ++i) {
      RooDisplayFont.Glyph glyph = glyphs.get(i);
      hexWriter.newLine();
      String comment = ("\"" + (char)glyph.getCodePoint() + "\"");
      comment += String.format(" (U+%04X)", glyph.getCodePoint());
      if (encodedGlyphs[i].compressed) {
        int bytesSaved = encodedGlyphs[i].getBytesSaved();
        comment +=
            ", RLE, " + bytesSaved + " byte" + (bytesSaved != 1 ? "s" : "") +
            " saved (" +
            String.format("%.1f", 100.0 * bytesSaved /
                                      encodedGlyphs[i].uncompressedSize) +
            "%)";
      } else {
        comment += ", uncompressed";
      }
      hexWriter.printComment(comment);
      hexWriter.newLine();
      hexWriter.printBuffer(encodedGlyphs[i].data);
    }

    // Add final statistics comment with placeholder.
    hexWriter.newLine();
    hexWriter.printComment("Total: @totalStats@.");

    hexWriter.end();

    // Now calculate actual byte counts for each section.
    int observedHeaderBytes = cmapStartBytes - headerStartBytes;
    int observedCmapBytes = glyphMetricsStartBytes - cmapStartBytes;
    int observedGlyphMetricsBytes = kerningStartBytes - glyphMetricsStartBytes;
    int observedKerningBytes = glyphDataStartBytes - kerningStartBytes;
    int observedGlyphDataBytes =
        hexWriter.getBytesWritten() - glyphDataStartBytes;
    int totalBytes = hexWriter.getBytesWritten();

    if (glyphMetricsStartBytes != glyphMetricsOffset) {
      throw new IllegalStateException(
          "Glyph metrics offset mismatch: expected " + glyphMetricsOffset +
          ", actual " + glyphMetricsStartBytes);
    }
    if (glyphDataStartBytes != glyphDataOffset) {
      throw new IllegalStateException("Glyph data offset mismatch: expected " +
                                      glyphDataOffset + ", actual " +
                                      glyphDataStartBytes);
    }

    if (kerningBytes != observedKerningBytes) {
      throw new IllegalStateException("Kerning size mismatch: expected " +
                                      kerningBytes + ", actual " +
                                      observedKerningBytes);
    }

    // Get the generated content as a string with placeholders.
    String content = buffer.toString();

    // Replace all placeholders with actual values.
    String glyphStatsText = glyphs.size() + " glyphs (" + compressedGlyphCount +
                            " compressed, " + uncompressedGlyphCount +
                            " uncompressed), " + totalBytes + " bytes total";
    if (totalBytesSaved > 0) {
      glyphStatsText += ", " + totalBytesSaved + " byte" +
                        (totalBytesSaved != 1 ? "s" : "") + " saved by RLE";
    }
    glyphStatsText += ".";
    content = content.replace("@glyphStats@", glyphStatsText);
    content = content.replace("@headerStats@", observedHeaderBytes + (" byte"
                                                                      + "s"));
    content = content.replace("@cmapStats@", glyphs.size() + " glyphs, " +
                                                 observedCmapBytes + " bytes");
    content = content.replace("@glyphMetricsStats@",
                              glyphs.size() + " glyphs, " +
                                  observedGlyphMetricsBytes + " bytes");
    if (kerningClasses.format == KERNING_FORMAT_CLASSES) {
      content = content.replace("@kerningDetails@",
                                kerningClasses.classCount + " classes, " +
                                    kerningClasses.sourceCount + " sources, " +
                                    kerningClasses.entryCount + " entries, " +
                                    observedKerningBytes + " bytes");
    } else if (kerningClasses.format == KERNING_FORMAT_PAIRS) {
      content = content.replace("@kerningDetails@",
                                kerningPairs.size() + " pairs, " +
                                    observedKerningBytes + " bytes");
    } else {
      content = content.replace("@kerningDetails@", "none");
    }
    content = content.replace("@glyphDataStats@",
                              observedGlyphDataBytes + " bytes");
    content = content.replace("@totalStats@", totalBytes + " bytes");
    content = content.replace("@totalBytes@", String.valueOf(totalBytes));

    // Write the final content with substituted values to the actual output.
    os.write(content);

    return totalBytes;
  }

  private KerningClasses
  encodeKerningClasses(List<RooDisplayFont.KerningPair> kerningPairs,
                       java.util.Map<Integer, Integer> codepointToGlyphIndex) {
    if (kerningPairs.isEmpty()) {
      return new KerningClasses(KERNING_FORMAT_NONE, 0, 0, 0,
                                java.util.Collections.emptyList(),
                                java.util.Collections.emptyList());
    }

    java.util.Map<Integer, java.util.List<KerningClassEntry>> bySource =
        new java.util.TreeMap<>();

    for (RooDisplayFont.KerningPair pair : kerningPairs) {
      RooDisplayFont.CodePointPair cp = pair.codePoints;
      Integer leftIndex = codepointToGlyphIndex.get(cp.left);
      Integer rightIndex = codepointToGlyphIndex.get(cp.right);
      if (leftIndex == null || rightIndex == null) {
        throw new IllegalArgumentException(
            "Kerning pair refers to missing glyph: " +
            String.format("U+%04X U+%04X", cp.left, cp.right));
      }
      if (pair.kern < 1 || pair.kern > 255) {
        throw new IllegalArgumentException("Kern outside range: " + pair.kern);
      }
      bySource.computeIfAbsent(leftIndex, k -> new java.util.ArrayList<>())
          .add(new KerningClassEntry(rightIndex, pair.kern));
    }

    java.util.Map<java.util.List<Integer>, Integer> classIds =
        new java.util.LinkedHashMap<>();
    java.util.List<java.util.List<KerningClassEntry>> classes =
        new java.util.ArrayList<>();
    java.util.List<KerningSourceEntry> sources = new java.util.ArrayList<>();

    for (java.util.Map.Entry<Integer, java.util.List<KerningClassEntry>> entry :
         bySource.entrySet()) {
      java.util.List<KerningClassEntry> dests = entry.getValue();
      dests.sort((a, b) -> Integer.compare(a.destGlyphIndex, b.destGlyphIndex));

      java.util.List<Integer> key = new java.util.ArrayList<>(dests.size());
      for (KerningClassEntry d : dests) {
        key.add((d.destGlyphIndex << 8) | (d.kern & 0xFF));
      }

      Integer classId = classIds.get(key);
      if (classId == null) {
        classId = classIds.size();
        classIds.put(key, classId);
        classes.add(dests);
      }
      sources.add(new KerningSourceEntry(entry.getKey(), classId));
    }

    if (classes.size() > 255) {
      throw new IllegalArgumentException("Too many kerning classes: " +
                                         classes.size());
    }

    int entryCount = 0;
    for (java.util.List<KerningClassEntry> cls : classes) {
      entryCount += cls.size();
    }

    return new KerningClasses(KERNING_FORMAT_CLASSES, sources.size(),
                              classes.size(), entryCount, sources, classes);
  }

  private List<KerningPairEntry>
  encodeKerningPairs(List<RooDisplayFont.KerningPair> kerningPairs,
                     java.util.Map<Integer, Integer> codepointToGlyphIndex) {
    List<KerningPairEntry> result = new java.util.ArrayList<>();
    for (RooDisplayFont.KerningPair pair : kerningPairs) {
      RooDisplayFont.CodePointPair cp = pair.codePoints;
      Integer leftIndex = codepointToGlyphIndex.get(cp.left);
      Integer rightIndex = codepointToGlyphIndex.get(cp.right);
      if (leftIndex == null || rightIndex == null) {
        throw new IllegalArgumentException(
            "Kerning pair refers to missing glyph: " +
            String.format("U+%04X U+%04X", cp.left, cp.right));
      }
      if (pair.kern < 1 || pair.kern > 255) {
        throw new IllegalArgumentException("Kern outside range: " + pair.kern);
      }
      result.add(new KerningPairEntry(leftIndex, rightIndex, pair.kern, cp.left,
                                      cp.right));
    }
    result.sort((a, b) -> {
      if (a.leftGlyphIndex != b.leftGlyphIndex) {
        return Integer.compare(a.leftGlyphIndex, b.leftGlyphIndex);
      }
      return Integer.compare(a.rightGlyphIndex, b.rightGlyphIndex);
    });
    return result;
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
      int uncompressedSize = result.length;
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
      return new EncodedGlyph(result, compressed, uncompressedSize);
    }
  }

  private static class FontMetricWriter {
    private final int fontMetricBytes;
    private final PayloadWriter writer;

    public FontMetricWriter(int fontMetricBytes, PayloadWriter writer) {
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
    private final PayloadWriter writer;

    public OffsetWriter(int offsetBytes, PayloadWriter writer) {
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
