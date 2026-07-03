package com.chachae.exam.vault;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionParserTest {

  private final QuestionParser parser = new QuestionParser(new ObjectMapper());

  @Test
  void parsePdfTextMergesSeparateAnswersPassageAndTables() {
    String text =
        "资料分析\n"
            + "根据以下资料，回答1-2题。\n"
            + "某市收入表如下：\n"
            + "年份 2022 2023\n"
            + "收入 100 120\n\n"
            + "1. 2023年收入比2022年多多少？ A. 10 B. 20 C. 30 D. 40\n"
            + "2、同比增长率约为多少？ （A）10% （B）20% （C）30% （D）40%\n\n"
            + "参考答案及解析\n"
            + "1.【答案】B【解析】2023年比2022年多20。\n"
            + "2. 答案：B 解析：20除以100约为20%。\n"
            + PdfTextExtractor.TABLE_SECTION_MARKER
            + "【第1页表格1】\n"
            + "年份 | 2022 | 2023\n"
            + "收入 | 100 | 120\n";

    List<Map<String, Object>> candidates = parser.parsePdfText(text, "sample.pdf");

    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).get("number")).isEqualTo(1);
    assertThat(candidates.get(0).get("module")).isEqualTo("资料分析");
    assertThat(candidates.get(0).get("answer")).isEqualTo("B");
    assertThat(candidates.get(0).get("explanation").toString()).contains("多20");
    assertThat(candidates.get(0).get("passageText").toString())
        .contains("根据以下资料")
        .contains("年份 | 2022 | 2023");

    @SuppressWarnings("unchecked")
    Map<String, String> firstOptions = (Map<String, String>) candidates.get(0).get("options");
    assertThat(firstOptions).containsEntry("D", "40");
    assertThat(candidates.get(1).get("answer")).isEqualTo("B");
  }
}
