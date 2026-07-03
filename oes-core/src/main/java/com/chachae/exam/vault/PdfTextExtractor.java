package com.chachae.exam.vault;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.PageIterator;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class PdfTextExtractor {

  public static final String TABLE_SECTION_MARKER = "\n\n【PDF表格抽取】\n";

  public String extract(MultipartFile file) throws IOException {
    try (PDDocument document = PDDocument.load(file.getInputStream())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      stripper.setAddMoreFormatting(true);
      String bodyText = stripper.getText(document);
      String tableText = extractTables(document);
      if (tableText.isEmpty()) {
        return bodyText;
      }
      return bodyText + TABLE_SECTION_MARKER + tableText;
    }
  }

  private String extractTables(PDDocument document) {
    StringBuilder builder = new StringBuilder();
    SpreadsheetExtractionAlgorithm spreadsheet = new SpreadsheetExtractionAlgorithm();
    BasicExtractionAlgorithm stream = new BasicExtractionAlgorithm();

    try (ObjectExtractor extractor = new ObjectExtractor(document)) {
      PageIterator pages = extractor.extract();
      int pageNumber = 1;
      while (pages.hasNext()) {
        Page page = pages.next();
        List<Table> tables = new ArrayList<>();
        tables.addAll(spreadsheet.extract(page));
        tables.addAll(stream.extract(page));
        appendUniqueTables(builder, tables, pageNumber);
        pageNumber++;
      }
    } catch (Exception ignored) {
      return "";
    }
    return builder.toString().trim();
  }

  private void appendUniqueTables(StringBuilder builder, List<Table> tables, int pageNumber) {
    Set<String> unique = new LinkedHashSet<>();
    int tableNumber = 1;
    for (Table table : tables) {
      String text = tableToText(table);
      if (text.length() < 20 || !unique.add(text)) {
        continue;
      }
      builder
          .append("【第")
          .append(pageNumber)
          .append("页表格")
          .append(tableNumber++)
          .append("】\n")
          .append(text)
          .append("\n");
    }
  }

  private String tableToText(Table table) {
    StringBuilder builder = new StringBuilder();
    for (List<RectangularTextContainer> row : table.getRows()) {
      List<String> cells = new ArrayList<>();
      for (RectangularTextContainer cell : row) {
        String text = normalizeCell(cell.getText());
        if (!text.isEmpty()) {
          cells.add(text);
        }
      }
      if (!cells.isEmpty()) {
        builder.append(String.join(" | ", cells)).append('\n');
      }
    }
    return builder.toString().trim();
  }

  private String normalizeCell(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
  }
}
