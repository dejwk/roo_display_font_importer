package roo.display;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Option;
import roo.display.imageimporter.CppPayloadFormatConverter;
import roo.display.imageimporter.ImportOptions.CppPayloadFormat;

// The main command-line interface.
class FontImporter {

  public static void main(String[] args) throws Throwable {
    try {
      CommandLine.call(new Main(), args);
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  @Command(
      description =
          "Imports specified fonts to be used with the roo.display library",
      name = "fontimporter", mixinStandardHelpOptions = true, version = "1.0")
  private static class Main implements Callable<Void> {

    @Option(names = {"-output-dir", "--output-dir"},
            description =
                "where to place resulting font files. Defaults to cwd.")
    File outputDir;

    @Option(names = "-font",
            description = "PostScript name of the font to generate.")
    private String inputFontName;

    @Option(names = "-sizes", description = "Font size(s) to generate.")
    private String fontSizes;

    @Option(names = "-suffix",
            description =
                "Suffix appended to font names for files and symbols.")
    private String nameSuffix;

    @Option(names = "-list",
            description = "Lists fonts available in the system.")
    private boolean listFonts;

    @Option(names = "-charset",
            defaultValue = "21-17F,3A9,3BC,3C0,2013-2014,2082-2085,20AC,20BF,2018-2022,"
                           + "2026,2030,2039-203A,2044,2122,2152,2202,2206,"
                           + "221A,221E,2248,2260,2264-2265,FB01-FB02",
            description = "Comma-separated list of character ranges to "
                          + "include (e.g., U+0020..U+007F",
            split = ",")
    private List<String> charsetRanges;

    @Option(names = "--cpp-payload-format", converter = CppPayloadFormatConverter.class,
        description = "C++ payload format: byte-list, string-literal-wrapper")
    private CppPayloadFormat cppPayloadFormat = CppPayloadFormat.BYTE_LIST;

    @Override
    public Void call() throws Exception {
      GraphicsEnvironment ge =
          GraphicsEnvironment.getLocalGraphicsEnvironment();
      Font[] fonts = ge.getAllFonts();
      Map<String, Font> map = new TreeMap<>();

      for (int i = 0; i < fonts.length; i++) {
        map.put(fonts[i].getPSName(), fonts[i]);
      }
      if (listFonts) {
        for (String name : map.keySet())
          System.out.println(name);
        return null;
      }

      if (inputFontName == null || fontSizes == null) {
        throw new IllegalArgumentException(
            "-font and -sizes are required; see -help.");
      }

      Map<TextAttribute, Object> attributes = new HashMap<>();
      attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
      boolean smooth = true;
      char[] charset = parseCharset(charsetRanges);
      File outDir = outputDir != null ? outputDir : new File(".");
      String suffix = nameSuffix == null ? "" : nameSuffix;
      System.out.println("Generating " + inputFontName + suffix);
      System.out.println("Output directory: " + outDir.getAbsolutePath());
      Font instance = map.get(inputFontName);
      if (instance == null) {
        System.out.println("FAILED: " + inputFontName + " not found.");
        return null;
      }

      float[] sizes = parseFontSizes(fontSizes);

      List<RooDisplayFont.CodePointPair> candidates = null;
      if (sizes.length > 1) {
        // Narrow down candidate kerning pairs by looking at all possible pairs
        // for the largest possible size.
        System.out.print("Identify kerning pair candidates... ");
        float largestSize = sizes[0];
        for (float size : sizes) {
          largestSize = Math.max(largestSize, size);
        }
        Font font = instance.deriveFont(attributes)
                        .deriveFont(Font.PLAIN, largestSize);
        RooDisplayFont f = new RooDisplayFont(font, smooth, charset);
        System.out.println(f.getGlyphCount());
        f.generateKerningPairs(null);
        candidates = new ArrayList<>();
        for (RooDisplayFont.KerningPair k : f.getKerningPairs()) {
          candidates.add(k.codePoints);
        }
        System.out.println("found " + candidates.size() + " candidate pairs.");
      }

      for (float fontSize : sizes) {
        Font font =
            instance.deriveFont(attributes).deriveFont(Font.PLAIN, fontSize);
        RooDisplayFont f = new RooDisplayFont(font, smooth, charset);
        System.out.print("Generating size " + fontSize + " ... ");
        f.generateKerningPairs(candidates);
        FontWriter writer = new FontWriter(outDir, true, cppPayloadFormat);
        FontEncoder encoder = new FontEncoder(f, suffix);
        int size = writer.writeFont(encoder, inputFontName + suffix, fontSize);
        System.out.print("Done (" + size + " bytes.)\n");
      }
      return null;
    }
  }

  private static float[] parseFontSizes(String fontSizes) {
    String[] sizeStrings = fontSizes.split(",");
    float[] sizes = new float[sizeStrings.length];
    for (int i = 0; i < sizeStrings.length; ++i) {
      float size = Float.parseFloat(sizeStrings[i].trim());
      if (!Float.isFinite(size) || size <= 0) {
        throw new IllegalArgumentException(
            "Font sizes must be finite, positive numbers.");
      }
      sizes[i] = size;
    }
    return sizes;
  }

  private static Pattern rangePattern =
      Pattern.compile("([Uu]\\+)?([0-9A-Fa-f]+)(\\-([Uu]\\+)?([0-9A-Fa-f]+))?");

  private static char[] parseCharset(List<String> charsetRanges) {
    List<Character> list = new ArrayList<>();
    for (String s : charsetRanges) {
      Matcher matcher = rangePattern.matcher(s);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid range specification: " + s);
      }
      int rangeStart = Integer.decode("0x" + matcher.group(2));
      int rangeEnd = rangeStart;
      if (matcher.group(3) != null) {
        rangeEnd = Integer.decode("0x" + matcher.group(5));
      }
      for (int i = rangeStart; i <= rangeEnd; i++) {
        list.add((char)i);
      }
    }
    char[] result = new char[list.size()];
    for (int i = 0; i < list.size(); i++) {
      result[i] = list.get(i);
    }
    return result;
  }
}
