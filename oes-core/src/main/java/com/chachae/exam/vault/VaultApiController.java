package com.chachae.exam.vault;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vault/api")
public class VaultApiController {

  private final PdfTextExtractor pdfTextExtractor;
  private final QuestionParser questionParser;
  private final VaultRepository repository;

  public VaultApiController(
      PdfTextExtractor pdfTextExtractor, QuestionParser questionParser, VaultRepository repository) {
    this.pdfTextExtractor = pdfTextExtractor;
    this.questionParser = questionParser;
    this.repository = repository;
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return repository.summary();
  }

  @GetMapping("/filters")
  public List<Map<String, Object>> filters() {
    return repository.filterOptions();
  }

  @PostMapping("/import/pdf")
  public Map<String, Object> importPdf(@RequestParam("file") MultipartFile file) throws Exception {
    String text = pdfTextExtractor.extract(file);
    List<Map<String, Object>> candidates = questionParser.parsePdfText(text, file.getOriginalFilename());
    return repository.createImportJob(file.getOriginalFilename(), "pdf", text, candidates);
  }

  @PostMapping("/import/text")
  public Map<String, Object> importText(@RequestBody Map<String, Object> payload) throws Exception {
    String sourceType = string(payload.get("sourceType"));
    String content = string(payload.get("content"));
    if (content == null) {
      throw new IllegalArgumentException("导入内容不能为空");
    }
    List<Map<String, Object>> candidates;
    if ("markdown".equalsIgnoreCase(sourceType) || "md".equalsIgnoreCase(sourceType)) {
      candidates = questionParser.parseMarkdown(content);
      sourceType = "markdown";
    } else {
      candidates = questionParser.parseJson(content);
      sourceType = "json";
    }
    return repository.createImportJob("manual-" + sourceType, sourceType, content, candidates);
  }

  @GetMapping("/import/jobs")
  public List<Map<String, Object>> importJobs() {
    return repository.listImportJobs();
  }

  @GetMapping("/import/jobs/{jobId}/candidates")
  public List<Map<String, Object>> candidates(
      @PathVariable Long jobId, @RequestParam(required = false) String status) {
    return repository.listCandidates(jobId, status);
  }

  @GetMapping("/import/candidates/{id}")
  public Map<String, Object> candidate(@PathVariable Long id) {
    return repository.getCandidate(id);
  }

  @PutMapping("/import/candidates/{id}")
  public Map<String, Object> updateCandidate(
      @PathVariable Long id, @RequestBody Map<String, Object> payload) {
    repository.updateCandidate(id, payload);
    return repository.getCandidate(id);
  }

  @PostMapping("/import/candidates/confirm")
  public Map<String, Object> confirmCandidates(@RequestBody Map<String, Object> payload) {
    List<Long> ids = longList(payload.get("ids"));
    Long jobId = payload.get("jobId") == null ? null : longValue(payload.get("jobId"));
    int count = repository.confirmCandidates(ids, jobId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("confirmedCount", count);
    return result;
  }

  @GetMapping("/papers")
  public List<Map<String, Object>> papers() {
    return repository.listPapers();
  }

  @GetMapping("/questions")
  public List<Map<String, Object>> questions(@RequestParam Map<String, Object> params) {
    return repository.listQuestions(params);
  }

  @GetMapping("/questions/{id}")
  public Map<String, Object> question(@PathVariable Long id) {
    return repository.getQuestion(id);
  }

  @PostMapping("/questions")
  public Map<String, Object> createQuestion(@RequestBody Map<String, Object> payload) {
    Long id = repository.createQuestion(payload);
    return repository.getQuestion(id);
  }

  @PutMapping("/questions/{id}")
  public Map<String, Object> updateQuestion(
      @PathVariable Long id, @RequestBody Map<String, Object> payload) {
    repository.updateQuestion(id, payload);
    return repository.getQuestion(id);
  }

  @DeleteMapping("/questions/{id}")
  public Map<String, Object> deleteQuestion(@PathVariable Long id) {
    repository.deleteQuestion(id);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("deleted", true);
    return result;
  }

  @PostMapping("/sessions")
  public Map<String, Object> createSession(@RequestBody Map<String, Object> payload) {
    return repository.createSession(payload);
  }

  @GetMapping("/sessions/{sessionId}")
  public Map<String, Object> session(@PathVariable Long sessionId) {
    return repository.getSession(sessionId);
  }

  @GetMapping("/sessions/{sessionId}/questions")
  public List<Map<String, Object>> sessionQuestions(@PathVariable Long sessionId) {
    return repository.listSessionQuestions(sessionId);
  }

  @PostMapping("/sessions/{sessionId}/records/{recordId}")
  public Map<String, Object> saveAnswer(
      @PathVariable Long sessionId,
      @PathVariable Long recordId,
      @RequestBody Map<String, Object> payload) {
    repository.saveAnswer(sessionId, recordId, payload);
    return repository.getSession(sessionId);
  }

  @PostMapping("/sessions/{sessionId}/submit")
  public Map<String, Object> submit(@PathVariable Long sessionId) {
    return repository.submitSession(sessionId);
  }

  @GetMapping("/sessions/{sessionId}/result")
  public Map<String, Object> result(@PathVariable Long sessionId) {
    return repository.sessionResult(sessionId);
  }

  @GetMapping("/history")
  public List<Map<String, Object>> history() {
    return repository.history();
  }

  @GetMapping("/prediction")
  public Map<String, Object> prediction() {
    return repository.prediction();
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, Object> handleException(Exception exception) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("error", exception.getMessage());
    return result;
  }

  private List<Long> longList(Object raw) {
    List<Long> ids = new ArrayList<>();
    if (raw instanceof List) {
      for (Object item : (List<?>) raw) {
        ids.add(longValue(item));
      }
    }
    return ids;
  }

  private Long longValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private String string(Object value) {
    if (value == null) {
      return null;
    }
    String string = String.valueOf(value).trim();
    return string.isEmpty() ? null : string;
  }
}
