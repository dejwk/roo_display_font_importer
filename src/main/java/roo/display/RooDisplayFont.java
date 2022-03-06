package roo.display;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RooDisplayFont {
  enum Charset {
    ASCII, UTF8
  }

  enum AlphaBits {
    ONE(0), // No antialiasing
    TWO(2), // 4 shades (2 intermediate alpha shades)
    FOUR(4), // 16 shades (14 intermediate alpha shades)
    EIGHT(8); // 256 shades ( 254 intermediate alpha shades)

    private final int code;

    AlphaBits(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }

    int bits() {
      return code();
    }

    int shades() {
      return 1 << bits();
    }
  }

  enum MaxFontSize {
    SINGLE, // max height: 256
    DOUBLE // hax height: 65536
  }

  public static class CodePointPair {
    public final int left;
    public final int right;

    CodePointPair(int left, int right) {
      this.left = left;
      this.right = right;
    }
  }

  public static class KerningPair {
    public final CodePointPair codePoints;
    public final int kern;

    KerningPair(CodePointPair codePoints, int kern) {
      this.codePoints = codePoints;
      this.kern = kern;
    }
  }

  public static class BoundingBox {
    public final int xMin;
    public final int yMin;
    public final int xMax;
    public final int yMax;

    public BoundingBox(int xMin, int yMin, int xMax, int yMax) {
      this.xMin = xMin;
      this.xMax = xMax;
      this.yMin = yMin;
      this.yMax = yMax;
    }

    public boolean contains(BoundingBox other) {
      return other.xMin >= xMin && other.xMax <= xMax && other.yMin >= yMin && other.yMax <= yMax;
    }

    public boolean isEmpty() {
      return xMin > xMax || yMin > yMax;
    }

    public BoundingBox expand(BoundingBox other) {
      if (other == null || contains(other))
        return this;
      if (other.contains(this))
        return other;
      return new BoundingBox(Math.min(xMin, other.xMin), Math.min(yMin, other.yMin), Math.max(xMax, other.xMax),
          Math.max(yMax, other.yMax));
    }

    public int getWidth() {
      return xMax - xMin + 1;
    }

    public int getHeight() {
      return yMax - yMin + 1;
    }
  }

  public static class Glyph {
    private final BoundingBox bbox;
    private final int codepoint;
    private final int advance;
    private final byte[] raster; // 256-level grayscale.

    public Glyph(BoundingBox bbox, int codepoint, int advance, byte[] raster) {
      this.bbox = bbox;
      this.codepoint = codepoint;
      this.advance = advance;
      this.raster = raster;
    }

    public final BoundingBox getBoundingBox() {
      return bbox;
    }

    public final int getAdvance() {
      return advance;
    }

    public final int getCodePoint() {
      return codepoint;
    }

    // Returns a value in Alpha8(Black).
    public int getPixelColor(int x, int y) {
      return raster[x + y * bbox.getWidth()] << 24;
    }

    // Checks if the specified row is entirely empty (white) at the specified
    // bit resolution.
    private static boolean isRowEmpty(BufferedImage img, int rowid, AlphaBits bits) {
      int w = img.getWidth();
      for (int i = 0; i < w; ++i) {
        int rgb = img.getRGB(i, rowid);
        if (!isEmpty(rgb, bits)) {
          return false;
        }
      }
      return true;
    }

    // Checks if the specified column is entirely empty (white) at the specified
    // bit resolution.
    private static boolean isColumnEmpty(BufferedImage img, int colid, AlphaBits bits) {
      int h = img.getHeight();
      for (int i = 0; i < h; ++i) {
        int rgb = img.getRGB(colid, i);
        if (!isEmpty(rgb, bits)) {
          return false;
        }
      }
      return true;
    }

    // Checks if the specified argb pixel is entirely empty (white) at the specified
    // bit resolution.
    private static boolean isEmpty(int rgb, AlphaBits bits) {
      // We expect grayscale, so just taking one component (B) and inverting it so
      // that zero represents 'empty'.
      int pixel = 0xFF - (rgb & 0xFF);
      switch (bits) {
        case FOUR: {
          // Rounds the color to the nearest 4-bit representation.
          return (byte) ((pixel - (pixel >> 5)) >> 4) == 0;
        }
        case EIGHT: {
          return pixel == 0;
        }
        default: {
          throw new IllegalArgumentException("Not currently supported: " + bits);
        }
      }
    }
  }

  public static class GlyphImporter {
    private Font font;
    private BufferedImage img;
    private Graphics2D graphics;
    private FontMetrics metrics;

    GlyphImporter(Font font) {
      this.font = font;
      int fontSize = font.getSize();
      int imgWidth = fontSize * 5;
      int imgHeight = fontSize * 5;
      this.img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
      this.graphics = img.createGraphics();
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setBackground(Color.WHITE);
      graphics.setColor(Color.BLACK);
      graphics.setFont(font);
      metrics = graphics.getFontMetrics();
    }

    Glyph importGlyph(char c, AlphaBits bits) {
      int xOffset = font.getSize() * 2;
      int yOffset = font.getSize() * 2;
      graphics.clearRect(0, 0, img.getWidth(), img.getHeight());
      graphics.drawString(String.valueOf(c), xOffset, yOffset);

      // Find margins to cut.
      int left = 0;
      int top = 0;
      int right = img.getWidth() - 1;
      int bottom = img.getHeight() - 1;
      while (top <= bottom && Glyph.isRowEmpty(img, bottom, bits)) {
        bottom--;
      }
      while (top <= bottom && Glyph.isRowEmpty(img, top, bits)) {
        top++;
      }
      while (left <= right && Glyph.isColumnEmpty(img, right, bits)) {
        right--;
      }
      while (left <= right && Glyph.isColumnEmpty(img, left, bits)) {
        left++;
      }
      if (top > bottom || left > right) {
        top = yOffset;
        bottom = yOffset - 1;
        left = xOffset;
        right = xOffset - 1;
      }
      // Note: bbox is in FreeType coords (y up).
      BoundingBox bbox = new BoundingBox(
          left - xOffset, yOffset - bottom, right - xOffset, yOffset - top);
      byte[] raster = new byte[bbox.getWidth() * bbox.getHeight()];
      int dstoffset = 0;
      for (int rowid = top; rowid <= bottom; ++rowid) {
        for (int colid = left; colid <= right; ++colid) {
          int rgb = img.getRGB(colid, rowid);
          raster[dstoffset++] = (byte) ((~rgb >> 8) & 0xFF);
        }
      }
      return new Glyph(bbox, (int) c, metrics.charWidth(c), raster);
    }
  }

  // Fields of RooDisplayFont.

  Font font;
  List<Glyph> glyphs = new ArrayList<>();
  Map<Integer, Glyph> glyphIdx = new HashMap<>();
  List<KerningPair> kerningPairs = new ArrayList<KerningPair>();
  final AlphaBits alphaBits = AlphaBits.FOUR;
  final Charset charset;
  int ascent;
  int descent;

  // Creates and initialized the RooDisplayFont, given the specified font and the charset.
  public RooDisplayFont(Font font, boolean smooth, char charset[]) {
    this.font = font;
    // Determine charset.
    boolean hasNonAscii = false;
    for (int i = 0; i < charset.length; ++i) {
      if (charset[i] >= 256) {
        hasNonAscii = true;
        break;
      }
    }
    this.charset = hasNonAscii ? Charset.UTF8 : Charset.ASCII;

    GlyphImporter glyphImporter = new GlyphImporter(font);

    for (int i = 0; i < charset.length; i++) {
      char c = charset[i];
      if (isWhitespace(c)) {
        continue;
      }
      if (!font.canDisplay(c)) {
        continue;
      }
      Glyph g = glyphImporter.importGlyph(c, alphaBits);
      if (g.getBoundingBox().isEmpty()) {
        continue;
      }
      glyphs.add(g);
      glyphIdx.put((int) c, g);
    }
    // Determine ascent and descent.
    Glyph d = getGlyphForCodepoint((int)'d');
    if (d != null) {
      ascent = d.getBoundingBox().yMax;
    }
    Glyph p = getGlyphForCodepoint((int)'p');
    if (p != null) {
      descent = p.getBoundingBox().yMin - 1;
    }
  }

  public Font getFont() {
    return font;
  }

  public int getGlyphCount() {
    return glyphs.size();
  }

  public Glyph getGlyphAtIndex(int idx) {
    return glyphs.get(idx);
  }

  public Glyph getGlyphForCodepoint(int codepoint) {
    return glyphIdx.get(codepoint);
  }

  // To minimize the need for quadratic complexity, you can provide candidate
  // super-set (e.g. coming from rendering the same font of larger size earlier).
  public void generateKerningPairs(List<CodePointPair> candidates) {
    kerningPairs.clear();
    if (candidates == null) {
      candidates = new ArrayList<>();
      for (int i = 0; i < getGlyphCount(); ++i) {
        Glyph g1 = getGlyphAtIndex(i);
        for (int j = 0; j < getGlyphCount(); ++j) {
          Glyph g2 = getGlyphAtIndex(j);
          candidates.add(new CodePointPair(g1.getCodePoint(), g2.getCodePoint()));
        }
      }
    }
    FontRenderContext cxt = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    char[] pair = new char[2];
    Glyph g1 = null;
    Glyph g2 = null;
    Rectangle2D boundsLeft = null;
    Rectangle2D boundsRight = null;
    for (CodePointPair candidate : candidates) {
      if (g1 == null || candidate.left != g1.getCodePoint()) {
        g1 = getGlyphForCodepoint((char) candidate.left);
        pair[0] = (char) g1.getCodePoint();
        boundsLeft = font.getStringBounds(pair, 0, 1, cxt);
      }
      if (g2 == null || candidate.right != g2.getCodePoint()) {
        g2 = getGlyphForCodepoint((char) candidate.right);
        pair[1] = (char) g2.getCodePoint();
        boundsRight = font.getStringBounds(pair, 1, 2, cxt);
      }
      Rectangle2D boundsPair = font.getStringBounds(pair, 0, 2, cxt);
      double kerning = boundsLeft.getWidth() + boundsRight.getWidth() - boundsPair.getWidth();
      if (kerning >= 1) {
        // System.out.println("Kerning: " + pair[0] + ", " + pair[1] + ": " + kerning);
        kerningPairs.add(new KerningPair(candidate, (int) (kerning - 0.0)));
      }
    }
  }

  public int getAscent() {
    return ascent;
  }

  public int getDescent() {
    return descent;
  }

  public AlphaBits getAlphaBits() {
    return alphaBits;
  }

  public Charset getCharset() {
    return charset;
  }

  public List<KerningPair> getKerningPairs() {
    return kerningPairs;
  }

  public List<Glyph> getGlyphs() {
    return glyphs;
  }

  private static boolean isWhitespace(int code) {
    return code == 0x0020 || code == 0x00A0 ||
        (code >= 0x0009 && code <= 0x000D) ||
        code == 0x1680 || code == 0x180E ||
        (code >= 0x2000 && code <= 0x200B) || code == 0x2028 ||
        code == 0x2029 || code == 0x205F || code == 0x3000 ||
        code == 0xFEFF || code == 0x00AD;
  }
}
