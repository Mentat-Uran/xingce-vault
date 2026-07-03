# xingce-vault 本地公考题库与模拟考试系统

## 底座选择

本项目选择 `illbnm/OES` 作为底座。

原因：

- OES 已有在线考试、试题导入、试卷导入、随机组卷、考试计时、自动交卷、自动批改和成绩分析，和本项目目标最接近。
- 技术栈是 Spring Boot + MyBatis-Plus + Beetl + AdminLTE，适合在现有考试系统上改造。
- 第一轮改造保留 OES 工程结构，新增 `/vault` 本地公考功能线；旧教师、学生、管理员语义暂不作为主入口。

## 当前实现

访问入口：

```bash
http://localhost:8080/vault
```

已实现第一轮可用闭环：

- 本地 H2 文件数据库，默认数据文件在 `data/xingce-vault*`。
- 文本型 PDF 上传入口，使用 PDFBox 提取正文文本。
- 使用 Tabula Java 抽取文本型 PDF 表格，优先服务资料分析材料校对。
- PDF 题号、选项、独立答案解析区、资料分析材料的规则切分与回填。
- JSON / Markdown 题目导入入口。
- ImportJob / ImportCandidate 候选题表，所有解析结果先进入人工校对。
- 候选题编辑、确认入库。
- Paper / Passage / Question / ExamSession / AnswerRecord 数据表。
- 题库列表、筛选、搜索、详情、编辑、删除。
- 原卷 / 智能组卷 / 练习组卷入口。
- 默认 120 分钟倒计时。
- 答题进度保存、每题累计用时、待回看标记。
- 手动交卷和超时自动交卷。
- 自动批改、总分、正确率、模块得分、模块用时、每题用时、错题列表。
- 历史成绩和规则版分数预测。

没有实现，也不会在第一阶段实现：

- 商业题库内置。
- 联网抓题或爬虫。
- 社区、排行榜、账号体系。
- 错因复盘、学习计划、间隔复习。
- 扫描版 OCR 自动识别。

## 运行

本机需要 Java 8+。当前验证环境是 Java 21。

如果本机没有 Maven，可以临时下载 Maven 后运行；已有 Maven 时直接用 `mvn`。

```bash
mvn -DskipTests package
java -jar oes-core/target/oes-core-1.0.jar
```

当前仓库验证命令：

```bash
/tmp/apache-maven-3.9.9/bin/mvn -DskipTests clean compile
/tmp/apache-maven-3.9.9/bin/mvn -DskipTests package
java -jar oes-core/target/oes-core-1.0.jar
```

H2 控制台：

```bash
http://localhost:8080/vault/h2
```

JDBC URL：

```bash
jdbc:h2:file:./data/xingce-vault
```

## 导入格式

JSON 支持单题数组，字段示例：

```json
{
  "paper": "2024 江苏省考 A 类行测",
  "year": 2024,
  "examType": "省考",
  "province": "江苏",
  "subject": "行测",
  "module": "资料分析",
  "questionType": "基期量",
  "passage": "材料内容，可为空",
  "question": "题干内容",
  "options": {
    "A": "选项A",
    "B": "选项B",
    "C": "选项C",
    "D": "选项D"
  },
  "answer": "B",
  "explanation": "解析内容",
  "source": "PDF导入 / 手动录入",
  "tags": ["时间陷阱", "单位换算"]
}
```

Markdown 支持：

```markdown
## 题目 1

【考试】江苏省考
【年份】2024
【科目】行测
【模块】资料分析
【题型】基期量
【标签】时间陷阱, 单位换算

【材料】
这里是资料分析材料，可以为空。

【题干】
这里是题干。

A. 选项A
B. 选项B
C. 选项C
D. 选项D

【答案】B

【解析】
这里是解析。
```

## OCR 扩展

当前 `PdfTextExtractor` 只做文本型 PDF 提取：PDFBox 负责正文文本，Tabula Java 负责可抽取表格；扫描版 OCR 仍保留为扩展点。接入 OCR 时保持同一流程：

```text
PDF / OCR / 版面解析 -> 文本或结构化块 -> QuestionParser -> ImportCandidate -> 人工校对 -> Question
```

推荐扩展点：

- Tabula Java：已接入，用于文本型 PDF 表格抽取，不处理扫描图片表格。
- PaddleOCR：在 `PdfTextExtractor` 增加扫描页识别分支，输出文本块、坐标和置信度。
- MinerU：新增版面解析服务，保留段落、表格、图片和资料分析材料块。
- PDF-Extract-Kit：输出题号、选项、答案解析候选，但仍必须进入校对页，不能直接入库。

无法确定的字段必须保留为空或 `unknown`，不要编造答案、解析或题型。
