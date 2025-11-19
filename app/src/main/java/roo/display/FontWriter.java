package roo.display;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

// Writes the encoded font to the output files.
class FontWriter {
  private final File libDir;
  private final boolean rle;

  FontWriter(File libDir, boolean rle) {
    this.libDir = libDir;
    this.rle = rle;
  }

  public int writeFont(FontEncoder encoder, String fontName, int fontSize) throws IOException {
    if (fontName == null) {
      RooDisplayFont font = encoder.getFont();
      fontName = font.getFont().getPSName() + "-" + (font.getAscent() + font.getDescent());
    }

    // System.out.println("Generating " + fontName + " into the directory " +
    // libDir);
    String fullFontName = "font_" + fontName.replaceAll("-", "_");
    File familyDir = new File(libDir, fontName.replaceAll("-", "_"));
    familyDir.mkdir();
    File outputHeaderFile = new File(familyDir, String.valueOf(fontSize) + ".h");
    File outputCppFile = new File(familyDir, String.valueOf(fontSize) + ".cpp");
    String varName = fullFontName.replaceAll("-", "_").replaceAll(" ", "_")
        + "_" + String.valueOf(fontSize);

    Writer headerWriter = new BufferedWriter(
        new OutputStreamWriter(new FileOutputStream(outputHeaderFile)));
    headerWriter.write("#include \"roo_display/font/font.h\"\n\n");
    headerWriter.write("namespace roo_display {\n\n");
    encoder.writeDeclaration(headerWriter, varName);
    headerWriter.write("\n\n}  // namespace roo_display\n");
    headerWriter.flush();
    headerWriter.close();

    Writer cppWriter = new BufferedWriter(
        new OutputStreamWriter(new FileOutputStream(outputCppFile)));
    cppWriter.write("#include \"" + String.valueOf(fontSize) + ".h\"\n");
    cppWriter.write("#include \"pgmspace.h\"\n");
    cppWriter.write("#include <inttypes.h>\n");
    cppWriter.write("#include \"roo_display/font/smooth_font.h\"\n\n");
    cppWriter.write("namespace roo_display {\n\n");
    int size = encoder.writeDefinition(cppWriter, varName + "_data", rle);

    cppWriter.write("\n");
    cppWriter.write("const Font& " + varName + "() {\n");
    cppWriter.write("  static SmoothFont font(" + varName + "_data" + ");\n");
    cppWriter.write("  return font;\n");
    cppWriter.write("}\n");

    cppWriter.write("\n}  // namespace roo_display\n");

    cppWriter.flush();
    cppWriter.close();
    return size;
  }
}
