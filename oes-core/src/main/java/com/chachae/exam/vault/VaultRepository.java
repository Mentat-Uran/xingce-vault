package com.chachae.exam.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Repository
public class VaultRepository {

  private static final int DEFAULT_DURATION_SECONDS = 7200;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public VaultRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Map<String, Object> createImportJob(
      String fileName, String sourceType, String rawText, List<Map<String, Object>> candidates) {
    Long jobId =
        insert(
            "INSERT INTO vault_import_jobs(file_name, source_type, raw_text, status) VALUES(?, ?, ?, ?)",
            ps -> {
              ps.setString(1, fileName);
              ps.setString(2, sourceType);
              ps.setString(3, rawText);
              ps.setString(4, "parsed");
            });

    for (Map<String, Object> candidate : candidates) {
      insertCandidate(jobId, candidate);
    }

    Map<String, Object> result = getImportJob(jobId);
    result.put("candidates", listCandidates(jobId, "pending"));
    return result;
  }

  public List<Map<String, Object>> listImportJobs() {
    return query(
        "SELECT id, file_name, source_type, status, created_at, updated_at, "
            + "(SELECT COUNT(*) FROM vault_import_candidates c WHERE c.import_job_id = j.id) AS candidate_count, "
            + "(SELECT COUNT(*) FROM vault_import_candidates c WHERE c.import_job_id = j.id AND c.status = 'confirmed') AS confirmed_count "
            + "FROM vault_import_jobs j ORDER BY id DESC");
  }

  public Map<String, Object> getImportJob(Long id) {
    return single(
        "SELECT id, file_name, source_type, status, created_at, updated_at FROM vault_import_jobs WHERE id = ?",
        id);
  }

  public List<Map<String, Object>> listCandidates(Long jobId, String status) {
    StringBuilder sql =
        new StringBuilder("SELECT * FROM vault_import_candidates WHERE import_job_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(jobId);
    if (notBlank(status)) {
      sql.append(" AND status = ?");
      args.add(status);
    }
    sql.append(" ORDER BY COALESCE(number, id), id");
    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
    for (Map<String, Object> row : rows) {
      inflateJsonFields(row);
    }
    return rows;
  }

  public Map<String, Object> getCandidate(Long id) {
    Map<String, Object> row = single("SELECT * FROM vault_import_candidates WHERE id = ?", id);
    inflateJsonFields(row);
    return row;
  }

  public void updateCandidate(Long id, Map<String, Object> payload) {
    Map<String, Object> candidate = getCandidate(id);
    candidate.putAll(payload);
    jdbcTemplate.update(
        "UPDATE vault_import_candidates SET paper_title = ?, number = ?, exam_type = ?, province = ?, "
            + "year = ?, subject = ?, module = ?, question_type = ?, passage_text = ?, stem = ?, "
            + "options_json = ?, answer = ?, explanation = ?, score = ?, tags_json = ?, source = ?, "
            + "status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        string(candidate.get("paperTitle")),
        integer(candidate.get("number")),
        string(candidate.get("examType")),
        string(candidate.get("province")),
        integer(candidate.get("year")),
        string(candidate.get("subject")),
        string(candidate.get("module")),
        string(candidate.get("questionType")),
        string(candidate.get("passageText")),
        string(candidate.get("stem")),
        json(candidate.get("options"), true),
        normalizeAnswer(string(candidate.get("answer"))),
        string(candidate.get("explanation")),
        decimal(candidate.get("score"), 1D),
        json(candidate.get("tags"), false),
        string(candidate.get("source")),
        defaultString(string(candidate.get("status")), "pending"),
        id);
  }

  @Transactional
  public int confirmCandidates(List<Long> ids, Long jobId) {
    List<Long> targetIds = new ArrayList<>();
    if (ids != null) {
      targetIds.addAll(ids);
    }
    if (targetIds.isEmpty() && jobId != null) {
      for (Map<String, Object> row :
          query(
              "SELECT id FROM vault_import_candidates WHERE import_job_id = ? AND status = 'pending'",
              jobId)) {
        targetIds.add(longValue(row.get("id")));
      }
    }

    int count = 0;
    for (Long id : targetIds) {
      Map<String, Object> candidate = getCandidate(id);
      if (!"pending".equals(string(candidate.get("status"))) || !notBlank(string(candidate.get("stem")))) {
        continue;
      }
      Long paperId = findOrCreatePaper(candidate);
      Long passageId = findOrCreatePassage(paperId, candidate);
      Long questionId = insertQuestion(candidate, paperId, passageId);
      jdbcTemplate.update(
          "UPDATE vault_import_candidates SET status = 'confirmed', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
          id);
      count++;
      if (questionId != null) {
        updatePaperQuestionCount(paperId);
      }
    }
    return count;
  }

  public List<Map<String, Object>> listPapers() {
    return query(
        "SELECT id, title, exam_type, province, year, subject, duration_seconds, total_questions, source, "
            + "created_at, updated_at FROM vault_papers ORDER BY year DESC NULLS LAST, id DESC");
  }

  public List<Map<String, Object>> listQuestions(Map<String, Object> filters) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT q.*, p.title AS paper_title, ps.content AS passage_text FROM vault_questions q "
                + "LEFT JOIN vault_papers p ON p.id = q.paper_id "
                + "LEFT JOIN vault_passages ps ON ps.id = q.passage_id WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    appendEquals(sql, args, "q.paper_id", filters.get("paperId"));
    appendEquals(sql, args, "q.exam_type", filters.get("examType"));
    appendEquals(sql, args, "q.province", filters.get("province"));
    appendEquals(sql, args, "q.year", filters.get("year"));
    appendEquals(sql, args, "q.subject", filters.get("subject"));
    appendEquals(sql, args, "q.module", filters.get("module"));
    appendEquals(sql, args, "q.question_type", filters.get("questionType"));
    if (notBlank(string(filters.get("keyword")))) {
      sql.append(
          " AND (LOWER(CAST(q.stem AS VARCHAR)) LIKE ? OR LOWER(CAST(q.explanation AS VARCHAR)) LIKE ?)");
      String keyword = "%" + string(filters.get("keyword")).toLowerCase(Locale.ROOT) + "%";
      args.add(keyword);
      args.add(keyword);
    }
    sql.append(" ORDER BY q.year DESC NULLS LAST, q.paper_id DESC NULLS LAST, q.number NULLS LAST, q.id DESC");
    Integer limit = integer(filters.get("limit"));
    sql.append(" LIMIT ?");
    args.add(limit == null ? 200 : Math.min(Math.max(limit, 1), 500));
    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
    for (Map<String, Object> row : rows) {
      inflateJsonFields(row);
    }
    return rows;
  }

  public Map<String, Object> getQuestion(Long id) {
    Map<String, Object> row =
        single(
            "SELECT q.*, p.title AS paper_title, ps.content AS passage_text FROM vault_questions q "
                + "LEFT JOIN vault_papers p ON p.id = q.paper_id "
                + "LEFT JOIN vault_passages ps ON ps.id = q.passage_id WHERE q.id = ?",
            id);
    inflateJsonFields(row);
    return row;
  }

  @Transactional
  public Long createQuestion(Map<String, Object> payload) {
    Long paperId = findOrCreatePaper(payload);
    Long passageId = findOrCreatePassage(paperId, payload);
    Long id = insertQuestion(payload, paperId, passageId);
    updatePaperQuestionCount(paperId);
    return id;
  }

  @Transactional
  public void updateQuestion(Long id, Map<String, Object> payload) {
    Map<String, Object> current = getQuestion(id);
    current.putAll(payload);
    Long paperId = current.get("paperId") == null ? findOrCreatePaper(current) : longValue(current.get("paperId"));
    Long passageId = findOrCreatePassage(paperId, current);
    jdbcTemplate.update(
        "UPDATE vault_questions SET paper_id = ?, passage_id = ?, number = ?, exam_type = ?, province = ?, "
            + "year = ?, subject = ?, module = ?, question_type = ?, stem = ?, options_json = ?, "
            + "answer = ?, explanation = ?, score = ?, tags_json = ?, source = ?, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ?",
        paperId,
        passageId,
        integer(current.get("number")),
        string(current.get("examType")),
        string(current.get("province")),
        integer(current.get("year")),
        string(current.get("subject")),
        string(current.get("module")),
        string(current.get("questionType")),
        string(current.get("stem")),
        json(current.get("options"), true),
        normalizeAnswer(string(current.get("answer"))),
        string(current.get("explanation")),
        decimal(current.get("score"), 1D),
        json(current.get("tags"), false),
        string(current.get("source")),
        id);
    updatePaperQuestionCount(paperId);
  }

  @Transactional
  public void deleteQuestion(Long id) {
    Map<String, Object> question = getQuestion(id);
    jdbcTemplate.update("DELETE FROM vault_questions WHERE id = ?", id);
    if (question.get("paperId") != null) {
      updatePaperQuestionCount(longValue(question.get("paperId")));
    }
  }

  @Transactional
  public Map<String, Object> createSession(Map<String, Object> payload) {
    String mode = defaultString(string(payload.get("mode")), "smart");
    int duration = integer(payload.get("durationSeconds")) == null ? DEFAULT_DURATION_SECONDS : integer(payload.get("durationSeconds"));
    List<Map<String, Object>> questions = selectSessionQuestions(payload, mode);
    if (questions.isEmpty()) {
      throw new IllegalArgumentException("没有可用于组卷的题目");
    }

    Long paperId = payload.get("paperId") == null ? null : longValue(payload.get("paperId"));
    String title = defaultString(string(payload.get("title")), "公考模拟考试");
    Long sessionId =
        insert(
            "INSERT INTO vault_exam_sessions(paper_id, mode, title, duration_seconds, started_at, total_questions, status) "
                + "VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 'ongoing')",
            ps -> {
              if (paperId == null) {
                ps.setObject(1, null);
              } else {
                ps.setLong(1, paperId);
              }
              ps.setString(2, mode);
              ps.setString(3, title);
              ps.setInt(4, duration);
              ps.setInt(5, questions.size());
            });

    int order = 1;
    for (Map<String, Object> question : questions) {
      Long questionId = longValue(question.get("id"));
      String answer = normalizeAnswer(string(question.get("answer")));
      final int questionOrder = order++;
      jdbcTemplate.update(
          "INSERT INTO vault_answer_records(exam_session_id, question_id, question_order, correct_answer) "
              + "VALUES(?, ?, ?, ?)",
          sessionId,
          questionId,
          questionOrder,
          answer);
    }
    return getSession(sessionId);
  }

  public Map<String, Object> getSession(Long sessionId) {
    Map<String, Object> session = single("SELECT * FROM vault_exam_sessions WHERE id = ?", sessionId);
    if ("ongoing".equals(string(session.get("status"))) && remainingSeconds(session) <= 0) {
      submitSession(sessionId);
      session = single("SELECT * FROM vault_exam_sessions WHERE id = ?", sessionId);
    }
    session.put("remainingSeconds", Math.max(0, remainingSeconds(session)));
    return session;
  }

  public List<Map<String, Object>> listSessionQuestions(Long sessionId) {
    getSession(sessionId);
    List<Map<String, Object>> rows =
        query(
            "SELECT r.id AS record_id, r.exam_session_id, r.question_id, r.question_order, r.user_answer, "
                + "r.correct_answer, r.is_correct, r.time_spent_seconds, r.first_enter_at, r.last_leave_at, "
                + "r.changed_times, r.marked_for_review, q.paper_id, q.passage_id, q.number, q.exam_type, "
                + "q.province, q.year, q.subject, q.module, q.question_type, q.stem, q.options_json, "
                + "q.answer, q.explanation, q.score, q.tags_json, ps.content AS passage_text "
                + "FROM vault_answer_records r "
                + "JOIN vault_questions q ON q.id = r.question_id "
                + "LEFT JOIN vault_passages ps ON ps.id = q.passage_id "
                + "WHERE r.exam_session_id = ? ORDER BY r.question_order",
            sessionId);
    for (Map<String, Object> row : rows) {
      inflateJsonFields(row);
    }
    return rows;
  }

  @Transactional
  public void saveAnswer(Long sessionId, Long recordId, Map<String, Object> payload) {
    Map<String, Object> session = getSession(sessionId);
    if (!"ongoing".equals(string(session.get("status")))) {
      throw new IllegalStateException("考试已提交，不能继续修改答案");
    }
    Map<String, Object> record =
        single(
            "SELECT * FROM vault_answer_records WHERE id = ? AND exam_session_id = ?",
            recordId,
            sessionId);
    String newAnswer = normalizeAnswer(string(payload.get("userAnswer")));
    String oldAnswer = normalizeAnswer(string(record.get("userAnswer")));
    boolean changed =
        (oldAnswer == null && newAnswer != null) || (oldAnswer != null && !oldAnswer.equals(newAnswer));
    int delta = integer(payload.get("timeDeltaSeconds")) == null ? 0 : Math.max(0, integer(payload.get("timeDeltaSeconds")));
    Boolean marked = booleanValue(payload.get("markedForReview"));
    jdbcTemplate.update(
        "UPDATE vault_answer_records SET user_answer = ?, time_spent_seconds = time_spent_seconds + ?, "
            + "first_enter_at = COALESCE(first_enter_at, CURRENT_TIMESTAMP), last_leave_at = CURRENT_TIMESTAMP, "
            + "changed_times = changed_times + ?, marked_for_review = COALESCE(?, marked_for_review), "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND exam_session_id = ?",
        newAnswer,
        delta,
        changed ? 1 : 0,
        marked,
        recordId,
        sessionId);
  }

  @Transactional
  public Map<String, Object> submitSession(Long sessionId) {
    Map<String, Object> session = single("SELECT * FROM vault_exam_sessions WHERE id = ?", sessionId);
    if (!"ongoing".equals(string(session.get("status")))) {
      return sessionResult(sessionId);
    }

    List<Map<String, Object>> records =
        query(
            "SELECT r.id, r.user_answer, r.correct_answer, q.score FROM vault_answer_records r "
                + "JOIN vault_questions q ON q.id = r.question_id WHERE r.exam_session_id = ?",
            sessionId);
    int total = records.size();
    int answered = 0;
    int correct = 0;
    double score = 0D;
    for (Map<String, Object> record : records) {
      String userAnswer = normalizeAnswer(string(record.get("userAnswer")));
      String correctAnswer = normalizeAnswer(string(record.get("correctAnswer")));
      boolean isCorrect = userAnswer != null && correctAnswer != null && userAnswer.equals(correctAnswer);
      if (userAnswer != null) {
        answered++;
      }
      if (isCorrect) {
        correct++;
        score += decimal(record.get("score"), 1D);
      }
      jdbcTemplate.update(
          "UPDATE vault_answer_records SET is_correct = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
          isCorrect,
          longValue(record.get("id")));
    }
    int wrong = answered - correct;
    double accuracy = total == 0 ? 0D : (double) correct / (double) total;
    jdbcTemplate.update(
        "UPDATE vault_exam_sessions SET submitted_at = CURRENT_TIMESTAMP, answered_count = ?, correct_count = ?, "
            + "wrong_count = ?, score = ?, accuracy = ?, status = 'submitted', updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = ?",
        answered,
        correct,
        wrong,
        score,
        accuracy,
        sessionId);
    return sessionResult(sessionId);
  }

  public Map<String, Object> sessionResult(Long sessionId) {
    Map<String, Object> session = single("SELECT * FROM vault_exam_sessions WHERE id = ?", sessionId);
    List<Map<String, Object>> questions = listSessionQuestions(sessionId);
    Map<String, ModuleStats> moduleStatsMap = new LinkedHashMap<>();
    List<Map<String, Object>> wrongQuestions = new ArrayList<>();
    int unanswered = 0;
    int totalTime = 0;
    for (Map<String, Object> question : questions) {
      String module = defaultString(string(question.get("module")), "unknown");
      ModuleStats stats = moduleStatsMap.computeIfAbsent(module, ModuleStats::new);
      boolean answered = notBlank(string(question.get("userAnswer")));
      boolean correct = Boolean.TRUE.equals(booleanValue(question.get("isCorrect")));
      int time = integer(question.get("timeSpentSeconds")) == null ? 0 : integer(question.get("timeSpentSeconds"));
      double score = decimal(question.get("score"), 1D);
      stats.total++;
      stats.timeSeconds += time;
      totalTime += time;
      if (!answered) {
        unanswered++;
      }
      if (correct) {
        stats.correct++;
        stats.score += score;
      } else {
        wrongQuestions.add(question);
      }
    }
    List<Map<String, Object>> modules = new ArrayList<>();
    for (ModuleStats stats : moduleStatsMap.values()) {
      modules.add(stats.toMap());
    }
    session.put("unansweredCount", unanswered);
    session.put("totalTimeSeconds", totalTime);
    session.put("moduleStats", modules);
    session.put("records", questions);
    session.put("wrongQuestions", wrongQuestions);
    return session;
  }

  public List<Map<String, Object>> history() {
    return query(
        "SELECT id, paper_id, mode, title, duration_seconds, started_at, submitted_at, total_questions, "
            + "answered_count, correct_count, wrong_count, score, accuracy, status, created_at, updated_at "
            + "FROM vault_exam_sessions ORDER BY id DESC LIMIT 50");
  }

  public Map<String, Object> prediction() {
    List<Map<String, Object>> submitted =
        query(
            "SELECT id, title, score, accuracy, submitted_at, total_questions FROM vault_exam_sessions "
                + "WHERE status = 'submitted' ORDER BY submitted_at DESC, id DESC LIMIT 30");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("history", submitted);
    result.put("examCount", submitted.size());
    if (submitted.isEmpty()) {
      result.put("stablePrediction", false);
      return result;
    }

    result.put("highestScore", submitted.stream().mapToDouble(r -> decimal(r.get("score"), 0D)).max().orElse(0D));
    result.put("lowestScore", submitted.stream().mapToDouble(r -> decimal(r.get("score"), 0D)).min().orElse(0D));
    result.put("recent3Average", average(submitted, 3));
    result.put("recent5Average", average(submitted, 5));
    result.put("recent10Average", average(submitted, 10));
    result.put("moduleAverages", moduleAverages());

    if (submitted.size() < 3) {
      result.put("stablePrediction", false);
      return result;
    }

    double base = submitted.size() >= 5 ? average(submitted, 5) : average(submitted, 3);
    double stddev = submitted.size() >= 5 ? stddev(submitted, 5, base) : stddev(submitted, submitted.size(), base);
    result.put("stablePrediction", submitted.size() >= 3);
    result.put("predictedScore", round(base));
    result.put("predictedLow", round(Math.max(0, base - stddev)));
    result.put("predictedHigh", round(base + stddev));

    double recent3 = average(submitted, 3);
    double recent10 = average(submitted, Math.min(10, submitted.size()));
    double diff = recent3 - recent10;
    if (diff > 2D) {
      result.put("trend", "近期上升");
    } else if (diff < -2D) {
      result.put("trend", "近期下降");
    } else {
      result.put("trend", "基本稳定");
    }
    addWeakAndSlowModules(result);
    return result;
  }

  public Map<String, Object> summary() {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("questionCount", count("vault_questions"));
    summary.put("paperCount", count("vault_papers"));
    summary.put("pendingCandidateCount", scalarInt("SELECT COUNT(*) FROM vault_import_candidates WHERE status = 'pending'"));
    summary.put("submittedSessionCount", scalarInt("SELECT COUNT(*) FROM vault_exam_sessions WHERE status = 'submitted'"));
    return summary;
  }

  public List<Map<String, Object>> filterOptions() {
    return query(
        "SELECT 'examType' AS type, exam_type AS value FROM vault_questions WHERE exam_type IS NOT NULL GROUP BY exam_type "
            + "UNION ALL SELECT 'province' AS type, province AS value FROM vault_questions WHERE province IS NOT NULL GROUP BY province "
            + "UNION ALL SELECT 'subject' AS type, subject AS value FROM vault_questions WHERE subject IS NOT NULL GROUP BY subject "
            + "UNION ALL SELECT 'module' AS type, module AS value FROM vault_questions WHERE module IS NOT NULL GROUP BY module "
            + "UNION ALL SELECT 'questionType' AS type, question_type AS value FROM vault_questions WHERE question_type IS NOT NULL GROUP BY question_type "
            + "ORDER BY type, value");
  }

  private void insertCandidate(Long jobId, Map<String, Object> candidate) {
    jdbcTemplate.update(
        "INSERT INTO vault_import_candidates(import_job_id, paper_title, number, exam_type, province, year, "
            + "subject, module, question_type, passage_text, stem, options_json, answer, explanation, score, "
            + "tags_json, source, status) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')",
        jobId,
        string(candidate.get("paperTitle")),
        integer(candidate.get("number")),
        string(candidate.get("examType")),
        string(candidate.get("province")),
        integer(candidate.get("year")),
        defaultString(string(candidate.get("subject")), "行测"),
        defaultString(string(candidate.get("module")), "unknown"),
        string(candidate.get("questionType")),
        string(candidate.get("passageText")),
        string(candidate.get("stem")),
        json(candidate.get("options"), true),
        normalizeAnswer(string(candidate.get("answer"))),
        string(candidate.get("explanation")),
        decimal(candidate.get("score"), 1D),
        json(candidate.get("tags"), false),
        string(candidate.get("source")));
  }

  private Long findOrCreatePaper(Map<String, Object> source) {
    String title =
        defaultString(
            string(firstNonNull(source.get("paperTitle"), source.get("title"))),
            defaultPaperTitle(source));
    Integer year = integer(source.get("year"));
    String province = string(source.get("province"));
    String subject = defaultString(string(source.get("subject")), "行测");
    List<Map<String, Object>> existing =
        query(
            "SELECT id FROM vault_papers WHERE title = ? AND COALESCE(year, -1) = COALESCE(?, -1) "
                + "AND COALESCE(province, '') = COALESCE(?, '') AND COALESCE(subject, '') = COALESCE(?, '') "
                + "ORDER BY id LIMIT 1",
            title,
            year,
            province,
            subject);
    if (!existing.isEmpty()) {
      return longValue(existing.get(0).get("id"));
    }
    return insert(
        "INSERT INTO vault_papers(title, exam_type, province, year, subject, duration_seconds, source) "
            + "VALUES(?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setString(1, title);
          ps.setString(2, string(source.get("examType")));
          ps.setString(3, province);
          if (year == null) {
            ps.setObject(4, null);
          } else {
            ps.setInt(4, year);
          }
          ps.setString(5, subject);
          ps.setInt(6, DEFAULT_DURATION_SECONDS);
          ps.setString(7, string(source.get("source")));
        });
  }

  private Long findOrCreatePassage(Long paperId, Map<String, Object> source) {
    String passageText = string(source.get("passageText"));
    if (!notBlank(passageText)) {
      return null;
    }
    List<Map<String, Object>> existing =
        query(
            "SELECT id FROM vault_passages WHERE paper_id = ? AND CAST(content AS VARCHAR) = ? ORDER BY id LIMIT 1",
            paperId,
            passageText);
    if (!existing.isEmpty()) {
      return longValue(existing.get(0).get("id"));
    }
    return insert(
        "INSERT INTO vault_passages(paper_id, title, content) VALUES(?, ?, ?)",
        ps -> {
          ps.setLong(1, paperId);
          ps.setString(2, "资料分析材料");
          ps.setString(3, passageText);
        });
  }

  private Long insertQuestion(Map<String, Object> source, Long paperId, Long passageId) {
    return insert(
        "INSERT INTO vault_questions(paper_id, passage_id, number, exam_type, province, year, subject, module, "
            + "question_type, stem, options_json, answer, explanation, score, tags_json, source) "
            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, paperId);
          ps.setObject(2, passageId);
          ps.setObject(3, integer(source.get("number")));
          ps.setString(4, string(source.get("examType")));
          ps.setString(5, string(source.get("province")));
          ps.setObject(6, integer(source.get("year")));
          ps.setString(7, defaultString(string(source.get("subject")), "行测"));
          ps.setString(8, defaultString(string(source.get("module")), "unknown"));
          ps.setString(9, string(source.get("questionType")));
          ps.setString(10, string(source.get("stem")));
          ps.setString(11, json(source.get("options"), true));
          ps.setString(12, normalizeAnswer(string(source.get("answer"))));
          ps.setString(13, string(source.get("explanation")));
          ps.setDouble(14, decimal(source.get("score"), 1D));
          ps.setString(15, json(source.get("tags"), false));
          ps.setString(16, string(source.get("source")));
        });
  }

  private void updatePaperQuestionCount(Long paperId) {
    if (paperId == null) {
      return;
    }
    jdbcTemplate.update(
        "UPDATE vault_papers SET total_questions = "
            + "(SELECT COUNT(*) FROM vault_questions WHERE paper_id = ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        paperId,
        paperId);
  }

  private List<Map<String, Object>> selectSessionQuestions(Map<String, Object> payload, String mode) {
    Long paperId = payload.get("paperId") == null ? null : longValue(payload.get("paperId"));
    if ("paper".equals(mode) && paperId != null) {
      return listQuestions(
          mapOf(
              "paperId",
              paperId,
              "limit",
              integer(payload.get("totalQuestions")) == null ? 500 : integer(payload.get("totalQuestions"))));
    }

    Map<String, Object> filters = new HashMap<>();
    filters.put("examType", payload.get("examType"));
    filters.put("province", payload.get("province"));
    filters.put("year", payload.get("year"));
    filters.put("subject", payload.get("subject"));
    Map<String, Integer> moduleCounts = moduleCounts(payload.get("moduleCounts"));
    int totalQuestions =
        integer(payload.get("totalQuestions")) == null ? sumModuleCounts(moduleCounts) : integer(payload.get("totalQuestions"));
    if (moduleCounts.isEmpty()) {
      moduleCounts.put("言语理解", 35);
      moduleCounts.put("判断推理", 35);
      moduleCounts.put("资料分析", 20);
      moduleCounts.put("数量关系", 10);
      moduleCounts.put("常识判断", 20);
      totalQuestions = integer(payload.get("totalQuestions")) == null ? 120 : totalQuestions;
    }

    List<Map<String, Object>> selected = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : moduleCounts.entrySet()) {
      filters.put("module", entry.getKey());
      filters.put("limit", 500);
      List<Map<String, Object>> pool = listQuestions(filters);
      selected.addAll(pickQuestions(pool, entry.getValue(), payload));
    }

    if (selected.size() < totalQuestions) {
      filters.remove("module");
      filters.put("limit", 500);
      List<Map<String, Object>> pool = listQuestions(filters);
      Set<Long> selectedIds = new HashSet<>();
      for (Map<String, Object> question : selected) {
        selectedIds.add(longValue(question.get("id")));
      }
      List<Map<String, Object>> remaining = new ArrayList<>();
      for (Map<String, Object> question : pool) {
        if (!selectedIds.contains(longValue(question.get("id")))) {
          remaining.add(question);
        }
      }
      selected.addAll(pickQuestions(remaining, totalQuestions - selected.size(), payload));
    }

    if (selected.size() > totalQuestions) {
      return new ArrayList<>(selected.subList(0, totalQuestions));
    }
    return selected;
  }

  private List<Map<String, Object>> pickQuestions(
      List<Map<String, Object>> pool, int count, Map<String, Object> payload) {
    if (pool.isEmpty() || count <= 0) {
      return new ArrayList<>();
    }
    Collections.shuffle(pool);
    if (Boolean.TRUE.equals(booleanValue(payload.get("preferUnanswered")))) {
      Set<Long> answeredIds = questionIds("SELECT DISTINCT question_id FROM vault_answer_records WHERE user_answer IS NOT NULL");
      pool.sort((left, right) -> Boolean.compare(answeredIds.contains(longValue(left.get("id"))), answeredIds.contains(longValue(right.get("id")))));
    }
    if (Boolean.TRUE.equals(booleanValue(payload.get("preferWrong")))) {
      Set<Long> wrongIds = questionIds("SELECT DISTINCT question_id FROM vault_answer_records WHERE is_correct = FALSE AND user_answer IS NOT NULL");
      pool.sort((left, right) -> Boolean.compare(!wrongIds.contains(longValue(left.get("id"))), !wrongIds.contains(longValue(right.get("id")))));
    }
    return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
  }

  private Set<Long> questionIds(String sql) {
    Set<Long> ids = new HashSet<>();
    for (Map<String, Object> row : query(sql)) {
      ids.add(longValue(row.get("questionId")));
    }
    return ids;
  }

  private Map<String, Integer> moduleCounts(Object raw) {
    Map<String, Integer> result = new LinkedHashMap<>();
    if (raw instanceof Map) {
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
        Integer value = integer(entry.getValue());
        String key = string(entry.getKey());
        if (key != null && value != null && value > 0) {
          result.put(key, value);
        }
      }
    }
    return result;
  }

  private int sumModuleCounts(Map<String, Integer> counts) {
    int total = 0;
    for (Integer value : counts.values()) {
      total += value;
    }
    return total;
  }

  private List<Map<String, Object>> moduleAverages() {
    List<Map<String, Object>> rows =
        query(
            "SELECT q.module, COUNT(*) AS total, SUM(CASE WHEN r.is_correct THEN 1 ELSE 0 END) AS correct, "
                + "AVG(r.time_spent_seconds) AS avg_time_seconds FROM vault_answer_records r "
                + "JOIN vault_questions q ON q.id = r.question_id "
                + "JOIN vault_exam_sessions s ON s.id = r.exam_session_id "
                + "WHERE s.status = 'submitted' GROUP BY q.module ORDER BY q.module");
    for (Map<String, Object> row : rows) {
      double total = decimal(row.get("total"), 0D);
      double correct = decimal(row.get("correct"), 0D);
      row.put("accuracy", total == 0D ? 0D : round(correct / total));
    }
    return rows;
  }

  private void addWeakAndSlowModules(Map<String, Object> result) {
    List<Map<String, Object>> modules = moduleAverages();
    String weak = null;
    String slow = null;
    double weakestAccuracy = Double.MAX_VALUE;
    double slowestTime = -1D;
    for (Map<String, Object> module : modules) {
      double accuracy = decimal(module.get("accuracy"), 1D);
      double time = decimal(module.get("avgTimeSeconds"), 0D);
      if (accuracy < weakestAccuracy) {
        weakestAccuracy = accuracy;
        weak = string(module.get("module"));
      }
      if (time > slowestTime) {
        slowestTime = time;
        slow = string(module.get("module"));
      }
    }
    result.put("weakestModule", weak);
    result.put("slowestModule", slow);
  }

  private double average(List<Map<String, Object>> rows, int size) {
    int count = Math.min(size, rows.size());
    if (count == 0) {
      return 0D;
    }
    double total = 0D;
    for (int i = 0; i < count; i++) {
      total += decimal(rows.get(i).get("score"), 0D);
    }
    return round(total / count);
  }

  private double stddev(List<Map<String, Object>> rows, int size, double average) {
    int count = Math.min(size, rows.size());
    if (count == 0) {
      return 0D;
    }
    double total = 0D;
    for (int i = 0; i < count; i++) {
      double diff = decimal(rows.get(i).get("score"), 0D) - average;
      total += diff * diff;
    }
    return Math.sqrt(total / count);
  }

  private int remainingSeconds(Map<String, Object> session) {
    if (!"ongoing".equals(string(session.get("status")))) {
      return 0;
    }
    int duration = integer(session.get("durationSeconds")) == null ? DEFAULT_DURATION_SECONDS : integer(session.get("durationSeconds"));
    Object startedAt = session.get("startedAt");
    if (!(startedAt instanceof Timestamp)) {
      return duration;
    }
    long elapsed = Duration.between(((Timestamp) startedAt).toInstant(), Instant.now()).getSeconds();
    return duration - (int) elapsed;
  }

  private void appendEquals(StringBuilder sql, List<Object> args, String column, Object value) {
    if (value == null || !notBlank(string(value))) {
      return;
    }
    sql.append(" AND ").append(column).append(" = ?");
    args.add(value);
  }

  private Long insert(String sql, PreparedStatementCallback callback) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        (Connection connection) -> {
          PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          callback.setValues(ps);
          return ps;
        },
        keyHolder);
    Number key = null;
    if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") instanceof Number) {
      key = (Number) keyHolder.getKeys().get("id");
    } else if (keyHolder.getKeys() != null && keyHolder.getKeys().get("ID") instanceof Number) {
      key = (Number) keyHolder.getKeys().get("ID");
    } else {
      key = keyHolder.getKey();
    }
    return key == null ? null : key.longValue();
  }

  private List<Map<String, Object>> query(String sql, Object... args) {
    return jdbcTemplate.query(sql, args, (rs, rowNum) -> row(rs));
  }

  private Map<String, Object> single(String sql, Object... args) {
    List<Map<String, Object>> rows = query(sql, args);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("数据不存在");
    }
    return rows.get(0);
  }

  private Map<String, Object> row(ResultSet rs) throws java.sql.SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    ResultSetMetaData metaData = rs.getMetaData();
    for (int i = 1; i <= metaData.getColumnCount(); i++) {
      String key = toCamel(metaData.getColumnLabel(i));
      Object value = rs.getObject(i);
      if (value instanceof Clob) {
        value = rs.getString(i);
      }
      row.put(key, value);
    }
    return row;
  }

  private void inflateJsonFields(Map<String, Object> row) {
    if (row.containsKey("optionsJson")) {
      row.put("options", parseJson(string(row.get("optionsJson")), new TypeReference<Map<String, Object>>() {}));
    }
    if (row.containsKey("tagsJson")) {
      row.put("tags", parseJson(string(row.get("tagsJson")), new TypeReference<List<Object>>() {}));
    }
  }

  private <T> T parseJson(String value, TypeReference<T> typeReference) {
    try {
      if (!notBlank(value)) {
        return objectMapper.readValue(typeReference.getType().getTypeName().contains("List") ? "[]" : "{}", typeReference);
      }
      return objectMapper.readValue(value, typeReference);
    } catch (Exception e) {
      try {
        return objectMapper.readValue(typeReference.getType().getTypeName().contains("List") ? "[]" : "{}", typeReference);
      } catch (Exception nested) {
        throw new IllegalStateException(nested);
      }
    }
  }

  private String json(Object value, boolean objectDefault) {
    try {
      if (value == null) {
        return objectDefault ? "{}" : "[]";
      }
      if (value instanceof String) {
        String string = string(value);
        return notBlank(string) ? string : (objectDefault ? "{}" : "[]");
      }
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return objectDefault ? "{}" : "[]";
    }
  }

  private int count(String table) {
    return scalarInt("SELECT COUNT(*) FROM " + table);
  }

  private int scalarInt(String sql) {
    Number number = jdbcTemplate.queryForObject(sql, Number.class);
    return number == null ? 0 : number.intValue();
  }

  private Object firstNonNull(Object left, Object right) {
    return left == null ? right : left;
  }

  private String defaultPaperTitle(Map<String, Object> source) {
    StringBuilder title = new StringBuilder();
    if (integer(source.get("year")) != null) {
      title.append(integer(source.get("year")));
    }
    if (notBlank(string(source.get("province")))) {
      title.append(string(source.get("province")));
    }
    if (notBlank(string(source.get("examType")))) {
      title.append(string(source.get("examType")));
    }
    if (notBlank(string(source.get("subject")))) {
      title.append(string(source.get("subject")));
    }
    return title.length() == 0 ? "未命名真题卷" : title.toString();
  }

  private Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
    Map<String, Object> map = new HashMap<>();
    map.put(key1, value1);
    map.put(key2, value2);
    return map;
  }

  private String toCamel(String label) {
    String value = label == null ? "" : label.toLowerCase(Locale.ROOT);
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '_') {
        upperNext = true;
      } else if (upperNext) {
        builder.append(Character.toUpperCase(ch));
        upperNext = false;
      } else {
        builder.append(ch);
      }
    }
    return builder.toString();
  }

  private Long longValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private Integer integer(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    String string = string(value);
    if (!notBlank(string)) {
      return null;
    }
    try {
      return Integer.parseInt(string);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Double decimal(Object value, Double defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private Boolean booleanValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private String string(Object value) {
    if (value == null) {
      return null;
    }
    String string = String.valueOf(value).trim();
    return string.isEmpty() ? null : string;
  }

  private String defaultString(String value, String defaultValue) {
    return value == null ? defaultValue : value;
  }

  private boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private String normalizeAnswer(String answer) {
    if (!notBlank(answer)) {
      return null;
    }
    String normalized = answer.trim().toUpperCase(Locale.ROOT);
    return normalized.matches("[A-D]") ? normalized : null;
  }

  private double round(double value) {
    return Math.round(value * 100D) / 100D;
  }

  private interface PreparedStatementCallback {
    void setValues(PreparedStatement ps) throws java.sql.SQLException;
  }

  private static class ModuleStats {
    private final String module;
    private int total;
    private int correct;
    private double score;
    private int timeSeconds;

    private ModuleStats(String module) {
      this.module = module;
    }

    private Map<String, Object> toMap() {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("module", module);
      map.put("total", total);
      map.put("correct", correct);
      map.put("score", score);
      map.put("accuracy", total == 0 ? 0D : Math.round(((double) correct / total) * 10000D) / 10000D);
      map.put("timeSeconds", timeSeconds);
      return map;
    }
  }
}
