package roo.display;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Option;

// The main command-line interface.
class FontImporter {

  public static void main(String[] args) throws Throwable {
    try {
      CommandLine.call(new Main(), args);
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  @Command(description = "Imports specified fonts to be used with the roo.display library", name = "fontimporter", mixinStandardHelpOptions = true, version = "1.0")
  private static class Main implements Callable<Void> {

    @Option(names = { "--output-dir" }, description = "where to place resulting font files. Defaults to cwd.")
    File outputDir;

    @Option(names = "-font", description = "PostScript name of the font to generate.")
    private String inputFontName;

    @Option(names = "-sizes", description = "Font size(s) to generate.")
    private String fontSizes;

    @Option(names = "-list", description = "Lists fonts available in the system.")
    private boolean listFonts;

    @Option(names = "-charset", defaultValue = "21-17F,3A9,3BC,3C0,2013-2014,20AC,20BF,2018-2022,2026,2030,2039-203A,2044,2122,2152,2202,2206,221A,221E,2248,2260,2264-2265,FB01-FB02", description = "Comma-separated list of character ranges to include (e.g., U+0020..U+007F", split = ",")
    private List<String> charsetRanges;

    @Override
    public Void call() throws Exception {
      GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
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
        throw new IllegalArgumentException("-font and -sizes are required; see -help.");
      }

      Map<TextAttribute, Object> attributes = new HashMap<>();
      attributes.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
      boolean smooth = true;
      char[] charset = parseCharset(charsetRanges);
      System.out.println("Generating " + inputFontName);
      Font instance = map.get(inputFontName);
      if (instance == null) {
        System.out.println("FAILED: " + inputFontName + " not found.");
        return null;
      }

      int[] sizes = Arrays.asList(fontSizes.split(",")).stream().map(String::trim).mapToInt(Integer::parseInt)
          .toArray();

      List<RooDisplayFont.CodePointPair> candidates = null;
      if (sizes.length > 1) {
        // Narrow down candidate kerning pairs by looking at all possible pairs for the
        // largest possible size.
        System.out.print("Identify kerning pair candidates... ");
        Font font = instance.deriveFont(attributes).deriveFont(Font.PLAIN, sizes[sizes.length - 1]);
        RooDisplayFont f = new RooDisplayFont(font, smooth, charset);
        System.out.println(f.getGlyphCount());
        f.generateKerningPairs(null);
        candidates = new ArrayList<>();
        for (RooDisplayFont.KerningPair k : f.getKerningPairs()) {
          candidates.add(k.codePoints);
        }
        System.out.println("found " + candidates.size() + " candidate pairs.");
      }

      for (int fontSize : sizes) {
        Font font = instance.deriveFont(attributes).deriveFont(Font.PLAIN, fontSize);
        RooDisplayFont f = new RooDisplayFont(font, smooth, charset);
        System.out.print("Generating size " + fontSize + " ... ");
        f.generateKerningPairs(candidates);
        FontWriter writer = new FontWriter(outputDir, true);
        FontEncoder encoder = new FontEncoder(f);
        int size = writer.writeFont(encoder, inputFontName, fontSize);
        System.out.print("Done (" + size + " bytes.)\n");
      }
      return null;
    }
  }

  private static Pattern rangePattern = Pattern.compile("([Uu]\\+)?([0-9A-Fa-f]+)(\\-([Uu]\\+)?([0-9A-Fa-f]+))?");

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
        list.add((char) i);
      }
    }
    char[] result = new char[list.size()];
    for (int i = 0; i < list.size(); i++) {
      result[i] = list.get(i);
    }
    return result;
  }
}
