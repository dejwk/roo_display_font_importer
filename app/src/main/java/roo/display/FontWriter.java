package roo.display;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import roo.display.imageimporter.CppPayloadSupport;
import roo.display.imageimporter.ImportOptions.CppPayloadFormat;

// Writes the encoded font to the output files.
class FontWriter {
  private final File libDir;
  private final boolean rle;
  private final CppPayloadFormat cppPayloadFormat;

  FontWriter(File libDir, boolean rle, CppPayloadFormat cppPayloadFormat) {
    this.libDir = libDir;
    this.rle = rle;
    this.cppPayloadFormat = cppPayloadFormat;
  }

  public int writeFont(FontEncoder encoder, String fontName, float fontSize) throws IOException {
    if (fontName == null) {
      RooDisplayFont font = encoder.getFont();
      fontName = font.getFont().getPSName() + "-" + (font.getAscent() + font.getDescent());
    }

    // System.out.println("Generating " + fontName + " into the directory " +
    // libDir);
    String fullFontName = "font_" + fontName.replaceAll("-", "_");
    File familyDir = new File(libDir, fontName.replaceAll("-", "_"));
    familyDir.mkdirs();
    String sizeName = getSizeName(fontSize);
    File outputHeaderFile = new File(familyDir, sizeName + ".h");
    File outputCppFile = new File(familyDir, sizeName + ".cpp");
    String varName = fullFontName.replaceAll("-", "_").replaceAll(" ", "_")
        + "_" + sizeName;

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
    cppWriter.write("#include \"" + sizeName + ".h\"\n");
    cppWriter.write("#include <inttypes.h>\n");
    if (usesStringLiteralPayloadWrapper()) {
      CppPayloadSupport.writeStringLiteralWrapperIncludes(cppWriter);
    }
    cppWriter.write("#include \"roo_display/hal/progmem.h\"\n");
    cppWriter.write("#include \"roo_display/font/smooth_font_v2.h\"\n\n");
    cppWriter.write("namespace roo_display {\n\n");
    if (usesStringLiteralPayloadWrapper()) {
      CppPayloadSupport.writeGeneratedPayloadHelper(cppWriter);
    }
    int size = encoder.writeDefinition(cppWriter, varName + "_data", rle, cppPayloadFormat);

    cppWriter.write("\n");
    cppWriter.write("const Font& " + varName + "() {\n");
    cppWriter.write("  static SmoothFontV2 font(" + getPayloadPointerExpression(varName + "_data")
        + ");\n");
    cppWriter.write("  return font;\n");
    cppWriter.write("}\n");

    cppWriter.write("\n}  // namespace roo_display\n");

    cppWriter.flush();
    cppWriter.close();
    return size;
  }

  private boolean usesStringLiteralPayloadWrapper() {
    return CppPayloadSupport.usesStringLiteralPayloadWrapper(cppPayloadFormat);
  }

  private String getPayloadPointerExpression(String dataVar) {
    return CppPayloadSupport.getPayloadPointerExpression(cppPayloadFormat, dataVar);
  }

  private static String getSizeName(float fontSize) {
    return new BigDecimal(Float.toString(fontSize)).stripTrailingZeros().toPlainString()
        .replace('.', '_');
  }
}
