package com.chachae.exam.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuestionParser {

  private static final Pattern QUESTION_START =
      Pattern.compile("(?m)^\\s*(?:第\\s*(\\d+)\\s*题|(\\d+)\\s*[.、．])\\s*");
  private static final Pattern ANSWER_PATTERN =
      Pattern.compile("(?:【\\s*答案\\s*】|答案\\s*[:：]|正确答案\\s*)\\s*([A-Da-d])");
  private static final Pattern EXPLANATION_PATTERN =
      Pattern.compile("(?s)(?:【\\s*解析\\s*】|解析\\s*[:：])\\s*(.*)$");
  private static final Pattern OPTION_PATTERN =
      Pattern.compile(
          "(?s)(?:^|\\n|\\s)(?:([A-Da-d])\\s*[.、．)]|[（(]([A-Da-d])[）)])\\s*(.*?)"
              + "(?=(?:\\n|\\s)(?:[A-Da-d]\\s*[.、．)]|[（(][A-Da-d][）)])|"
              + "(?:【\\s*答案\\s*】|答案\\s*[:：]|正确答案\\s*|【\\s*解析\\s*】|解析\\s*[:：])|$)");

  private final ObjectMapper objectMapper;

  public QuestionParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<Map<String, Object>> parsePdfText(String text, String sourceName) {
    String normalized = normalizeText(text);
    List<int[]> ranges = questionRanges(normalized);
    List<Map<String, Object>> candidates = new ArrayList<>();

    if (ranges.isEmpty()) {
      Map<String, Object> candidate = baseCandidate(sourceName);
      candidate.put("stem", trimToNull(normalized));
      candidates.add(candidate);
      return candidates;
    }

    for (int[] range : ranges) {
      String block = normalized.substring(range[0], range[1]).trim();
      if (!block.isEmpty()) {
        candidates.add(parseBlock(block, sourceName));
      }
    }
    return candidates;
  }

  public List<Map<String, Object>> parseJson(String content) throws IOException {
    Object raw = objectMapper.readValue(content, Object.class);
    List<?> items;
    if (raw instanceof List) {
      items = (List<?>) raw;
    } else if (raw instanceof Map && ((Map<?, ?>) raw).get("questions") instanceof List) {
      items = (List<?>) ((Map<?, ?>) raw).get("questions");
    } else {
      items = Arrays.asList(raw);
    }

    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Object item : items) {
      if (item instanceof Map) {
        candidates.add(fromImportedMap((Map<?, ?>) item, "JSON导入"));
      }
    }
    return candidates;
  }

  public List<Map<String, Object>> parseMarkdown(String content) {
    String[] blocks = content.split("(?m)^##\\s*题目\\s*\\d+\\s*$");
    List<Map<String, Object>> candidates = new ArrayList<>();
    int number = 1;
    for (String block : blocks) {
      String trimmed = block.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      Map<String, Object> candidate = baseCandidate("Markdown导入");
      candidate.put("number", number++);
      candidate.put("paperTitle", field(trimmed, "试卷"));
      candidate.put("examType", field(trimmed, "考试"));
      candidate.put("year", parseInteger(field(trimmed, "年份")));
      candidate.put("province", field(trimmed, "省份"));
      candidate.put("subject", defaultString(field(trimmed, "科目"), "行测"));
      candidate.put("module", defaultString(field(trimmed, "模块"), "unknown"));
      candidate.put("questionType", field(trimmed, "题型"));
      candidate.put("passageText", field(trimmed, "材料"));
      candidate.put("stem", field(trimmed, "题干"));
      candidate.put("answer", normalizeAnswer(field(trimmed, "答案")));
      candidate.put("explanation", field(trimmed, "解析"));
      candidate.put("tags", splitTags(field(trimmed, "标签")));
      candidate.put("options", parseMarkdownOptions(trimmed));
      candidates.add(candidate);
    }
    return candidates;
  }

  private Map<String, Object> parseBlock(String block, String sourceName) {
    Map<String, Object> candidate = baseCandidate(sourceName);
    Matcher startMatcher = QUESTION_START.matcher(block);
    int contentStart = 0;
    if (startMatcher.find()) {
      candidate.put("number", parseInteger(firstNotBlank(startMatcher.group(1), startMatcher.group(2))));
      contentStart = startMatcher.end();
    }

    String content = block.substring(contentStart).trim();
    Matcher answerMatcher = ANSWER_PATTERN.matcher(content);
    if (answerMatcher.find()) {
      candidate.put("answer", normalizeAnswer(answerMatcher.group(1)));
    }

    Matcher explanationMatcher = EXPLANATION_PATTERN.matcher(content);
    if (explanationMatcher.find()) {
      candidate.put("explanation", trimToNull(explanationMatcher.group(1)));
    }

    Map<String, String> options = new LinkedHashMap<>();
    Matcher optionMatcher = OPTION_PATTERN.matcher(content);
    int firstOptionStart = -1;
    while (optionMatcher.find()) {
      if (firstOptionStart < 0) {
        firstOptionStart = optionMatcher.start();
      }
      String key = normalizeAnswer(firstNotBlank(optionMatcher.group(1), optionMatcher.group(2)));
      String value = trimToNull(optionMatcher.group(3));
      if (key != null && value != null) {
        options.put(key, stripTrailingAnswerAndExplanation(value));
      }
    }
    candidate.put("options", options);

    String stemSource = firstOptionStart >= 0 ? content.substring(0, firstOptionStart) : content;
    candidate.put("stem", stripTrailingAnswerAndExplanation(stemSource));
    return candidate;
  }

  private List<int[]> questionRanges(String text) {
    List<Integer> starts = new ArrayList<>();
    Matcher matcher = QUESTION_START.matcher(text);
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    List<int[]> ranges = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      int start = starts.get(i);
      int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
      ranges.add(new int[] {start, end});
    }
    return ranges;
  }

  private Map<String, Object> fromImportedMap(Map<?, ?> raw, String source) {
    Map<String, Object> candidate = baseCandidate(source);
    candidate.put("paperTitle", stringValue(firstValue(raw, "paper", "paperTitle", "title")));
    candidate.put("number", parseInteger(firstValue(raw, "number", "questionNumber")));
    candidate.put("examType", stringValue(firstValue(raw, "examType", "exam_type")));
    candidate.put("province", stringValue(raw.get("province")));
    candidate.put("year", parseInteger(raw.get("year")));
    candidate.put("subject", defaultString(stringValue(raw.get("subject")), "行测"));
    candidate.put("module", defaultString(stringValue(raw.get("module")), "unknown"));
    candidate.put("questionType", stringValue(firstValue(raw, "questionType", "question_type")));
    candidate.put("passageText", stringValue(firstValue(raw, "passage", "passageText")));
    candidate.put("stem", stringValue(firstValue(raw, "question", "stem")));
    candidate.put("options", normalizeOptions(raw.get("options")));
    candidate.put("answer", normalizeAnswer(stringValue(raw.get("answer"))));
    candidate.put("explanation", stringValue(raw.get("explanation")));
    candidate.put("source", defaultString(stringValue(raw.get("source")), source));
    Object tags = raw.get("tags");
    candidate.put("tags", tags instanceof List ? tags : splitTags(stringValue(tags)));
    return candidate;
  }

  private Map<String, Object> baseCandidate(String sourceName) {
    Map<String, Object> candidate = new LinkedHashMap<>();
    candidate.put("paperTitle", null);
    candidate.put("number", null);
    candidate.put("examType", null);
    candidate.put("province", null);
    candidate.put("year", null);
    candidate.put("subject", "行测");
    candidate.put("module", "unknown");
    candidate.put("questionType", null);
    candidate.put("passageText", null);
    candidate.put("stem", null);
    candidate.put("options", new LinkedHashMap<String, String>());
    candidate.put("answer", null);
    candidate.put("explanation", null);
    candidate.put("score", 1);
    candidate.put("tags", new ArrayList<String>());
    candidate.put("source", sourceName);
    return candidate;
  }

  private String normalizeText(String text) {
    if (text == null) {
      return "";
    }
    return text.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n');
  }

  private String stripTrailingAnswerAndExplanation(String value) {
    if (value == null) {
      return null;
    }
    String result =
        value.replaceAll("(?s)(?:【\\s*答案\\s*】|答案\\s*[:：]|正确答案\\s*)[A-Da-d].*$", "");
    result = result.replaceAll("(?s)(?:【\\s*解析\\s*】|解析\\s*[:：]).*$", "");
    return trimToNull(result);
  }

  private Map<String, String> parseMarkdownOptions(String block) {
    Map<String, String> options = new LinkedHashMap<>();
    Matcher matcher = OPTION_PATTERN.matcher(block);
    while (matcher.find()) {
      String key = normalizeAnswer(firstNotBlank(matcher.group(1), matcher.group(2)));
      String value = stripTrailingAnswerAndExplanation(matcher.group(3));
      if (key != null && value != null) {
        options.put(key, value);
      }
    }
    return options;
  }

  private Map<String, String> normalizeOptions(Object raw) {
    Map<String, String> options = new LinkedHashMap<>();
    if (raw instanceof Map) {
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
        String key = normalizeAnswer(stringValue(entry.getKey()));
        String value = stringValue(entry.getValue());
        if (key != null && value != null) {
          options.put(key, value);
        }
      }
    }
    return options;
  }

  private String field(String block, String name) {
    Pattern pattern =
        Pattern.compile("(?s)【\\s*" + Pattern.quote(name) + "\\s*】\\s*(.*?)(?=\\n【|\\n[A-Da-d][.、．]|$)");
    Matcher matcher = pattern.matcher(block);
    if (matcher.find()) {
      return trimToNull(matcher.group(1));
    }
    return null;
  }

  private List<String> splitTags(String raw) {
    List<String> tags = new ArrayList<>();
    if (raw == null) {
      return tags;
    }
    for (String item : raw.split("[,，、]")) {
      String tag = trimToNull(item);
      if (tag != null) {
        tags.add(tag);
      }
    }
    return tags;
  }

  private Object firstValue(Map<?, ?> raw, String... keys) {
    for (String key : keys) {
      if (raw.containsKey(key)) {
        return raw.get(key);
      }
    }
    return null;
  }

  private Integer parseInteger(Object raw) {
    String value = stringValue(raw);
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String normalizeAnswer(String answer) {
    String value = trimToNull(answer);
    if (value == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("[A-Da-d]").matcher(value);
    if (matcher.find()) {
      return matcher.group().toUpperCase();
    }
    return null;
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String) {
      return trimToNull((String) value);
    }
    return trimToNull(String.valueOf(value));
  }

  private String defaultString(String value, String defaultValue) {
    return value == null ? defaultValue : value;
  }

  private String firstNotBlank(String left, String right) {
    String leftValue = trimToNull(left);
    return leftValue != null ? leftValue : trimToNull(right);
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException e) {
      throw new IllegalArgumentException("JSON serialization failed", e);
    }
  }

  public Map<String, Object> jsonToMap(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      return new LinkedHashMap<>();
    }
  }
}
