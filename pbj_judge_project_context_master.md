# PBJ Judge — Project Context Master Document

## 1. Tổng Quan Dự Án

PBJ Judge là một hệ thống Online Judge tích hợp AI để:
- Sinh đề bài tự động.
- Sinh testcase tự động.
- Chấm bài lập trình.
- Sandbox thực thi code an toàn.
- Hỗ trợ pipeline AI để phân tích đề và tạo bộ test chất lượng.

Hệ thống hướng tới mô hình tương tự:
- LeetCode
- Codeforces
- DOMJudge
- HackerRank

Nhưng được mở rộng bằng AI pipeline.

---

# 2. Tech Stack

## Backend
- Java 21
- Spring Boot 3.2.x
- Spring Web
- Spring Data JPA
- Spring AOP
- Maven

## Frontend
- Thymeleaf
- HTML5
- Tailwind CSS (CDN)

## Database
- MySQL 8
- Master / Slave architecture

## AI
- Google Gemini
- OpenAI ChatGPT (GPT-4o)
- LangChain4j

## Infrastructure
- Docker
- Docker Compose

---

# 3. Mục Tiêu Hệ Thống

PBJ Judge không chỉ là Online Judge thông thường.

Core objective:

1. AI phân tích đề bài.
2. AI sinh:
   - generator
   - golden solution
   - validator
   - edge cases
3. Backend chạy generator thật.
4. Backend sinh hàng loạt testcase.
5. Backend chạy golden solution để sinh expected output.
6. Sandbox verify chất lượng testcase.
7. User submit code.
8. Judge chấm verdict.

---

# 4. Kiến Trúc Tổng Thể

## High-Level Flow

```text
User
  ↓
Frontend (Thymeleaf)
  ↓
ProblemController
  ↓
ProblemService
  ↓
AI Integration Service
  ↓
Gemini / OpenAI
  ↓
AI Response JSON
  ↓
Generator + Golden Solution
  ↓
Sandbox Execution
  ↓
Testcase Files (.in/.out)
  ↓
Database
  ↓
Judge Runtime
  ↓
Verdict
```

---

# 5. Database Architecture

## Master-Slave

### mysql-master
Port: 3307

Responsibilities:
- INSERT
- UPDATE
- DELETE

### mysql-slave
Port: 3308

Responsibilities:
- SELECT

## Routing Strategy

Spring AOP + Transactional routing:

- `@Transactional(readOnly = true)`
  → route to slave.

- write transaction
  → route to master.

## Important Note

Hiện tại môi trường dev:
- slave đang có thể trỏ về master.
- mục đích tránh replication inconsistency.

---

# 6. AI Pipeline (Cực Quan Trọng)

## Stage 1 — User Input

User nhập:
- title
- statement
- constraints
- image (optional)

---

## Stage 2 — AI Analysis

Gemini phân tích:

AI trả về:

```json
{
  "problem_statement": "...",
  "constraints": "...",
  "generator_code": "...",
  "golden_solution": "...",
  "validator": "...",
  "edge_cases": [...]
}
```

---

## Stage 3 — Generator Execution

Backend KHÔNG yêu cầu AI sinh raw testcase lớn.

Backend chỉ yêu cầu:
- generator code
- seed strategy
- edge logic

Sau đó backend:
- compile generator
- chạy generator nhiều lần
- sinh testcase thật

Đây là nguyên tắc cực quan trọng.

## ABSOLUTE RULE

AI sinh CODE.
AI KHÔNG sinh data lớn trực tiếp.

---

## Stage 4 — Golden Solution

Golden solution:
- compile
- run trên từng input
- sinh expected output

Output lưu thành:

```text
001.in
001.out
002.in
002.out
```

---

## Stage 5 — Verdict Separation

System verify testcase quality.

### AC Probe
Correct solution phải pass.

### WA Probe
Wrong solution phải fail.

### TLE Probe
Slow solution phải timeout.

Nếu testcase không distinguish được:
- regenerate.

---

# 7. Sandbox Architecture

## Mục tiêu

Chạy code user an toàn.

## Protection

- CPU limit
- memory limit
- timeout
- process isolation
- chống fork bomb
- chống infinite loop
- chống OOM

## Runtime Flow

```text
Source Code
  ↓
Write temp file
  ↓
Compile
  ↓
Execute in sandbox
  ↓
Inject testcase input
  ↓
Capture stdout
  ↓
Compare expected output
  ↓
Verdict
```

## Verdicts

- AC
- WA
- TLE
- MLE
- RE
- CE

---

# 8. Async Architecture

Các tác vụ nặng chạy async:

- AI generation
- testcase generation
- judging
- regeneration

## Components

### AsyncConfig
Thread pool configuration.

### JobQueueService
Queue management.

### Frontend Polling
Frontend polling để lấy trạng thái job.

---

# 9. Core Services

## AiIntegrationService

Responsibilities:
- connect Gemini
- connect Groq
- parse AI JSON
- retry logic
- validation

AI Roles:

### Gemini
- problem understanding
- structured reasoning
- code generation

### Groq
- high-speed testcase generation
- Llama inference

---

## CodeExecutionService

Responsibilities:
- compile code
- run sandbox
- resource limits
- stdout capture
- verdict generation

---

## ProblemService

Business logic:
- create problem
- regenerate testcase
- fetch problem
- validation pipeline

---

## TestCaseStorageService

Responsibilities:
- save `.in`
- save `.out`
- organize testcase folders

Structure:

```text
testcase_data/problem_X/
```

---

# 10. Frontend Flow

## Main Pages

### index.html
Danh sách bài tập.

### create.html
Tạo bài toán mới.

### problem.html
Chi tiết bài toán + submit.

---

# 11. Submission Flow

```text
User submit code
  ↓
Backend compile
  ↓
Sandbox execute
  ↓
Run all testcases
  ↓
Compare outputs
  ↓
Return verdict
```

---

# 12. Docker Architecture

## Containers

### pbj-judge-app
Port: 8080

### mysql-master
Port: 3307

### mysql-slave
Port: 3308

---

## Important Commands

### Build

```bash
mvn clean package -DskipTests
```

### Start

```bash
docker-compose up -d --build
```

### Logs

```bash
docker logs pbj-judge-app --tail 50 -f
```

### Stop

```bash
docker-compose down
```

### Destroy volumes

```bash
docker-compose down -v
```

---

# 13. Environment Variables

```env
GEMINI_API_KEY=...
OPENAI_API_KEY=...
```

`.env` nằm trong `.gitignore`.

Không commit.

---

# 14. Current Project Structure

```text
PBJ_JUDGE/
├── src/
│   └── main/
│       ├── java/com/pbj/
│       │   ├── config/
│       │   ├── controller/
│       │   ├── dto/
│       │   ├── entity/
│       │   ├── repository/
│       │   └── service/
│       └── resources/
│           ├── application.yml
│           └── templates/
├── mysql/
├── testcase_data/
├── docker-compose.yml
├── Dockerfile
├── pom.xml
└── .env
```

---

# 15. Design Philosophy

## Principle 1

AI should generate:
- algorithms
- generators
- validators
- strategies

NOT huge raw datasets.

---

## Principle 2

Backend is source of truth.

AI output phải được:
- validated
- compiled
- executed
- verified

---

## Principle 3

Judge must be deterministic.

- reproducible testcase
- stable outputs
- isolated execution

---

# 16. Important Engineering Rules

## NEVER

❌ AI generate massive raw testcase.

❌ Trust AI outputs directly.

❌ Execute unverified code outside sandbox.

❌ Store gigantic testcase blobs in DB.

---

## ALWAYS

✅ Generate code.

✅ Run generator backend-side.

✅ Validate testcase quality.

✅ Separate AC/WA/TLE.

✅ Limit resources.

---

# 17. Common Problems

## Docker startup failure

Usually:
- MySQL not healthy yet.

Fix:
- wait
- restart compose.

---

## AI 401 Unauthorized

Cause:
- invalid key
- expired quota.

---

## BUILD FAILURE

Usually:
- wrong Java version.

Required:

```bash
java -version
```

Must be Java 21.

---

## Infinite Redirect Loop

Cause:
- slave DB inconsistency.

---

# 18. Future Expansion Ideas

## Possible Features

### Multi-language judge
- C++
- Java
- Python
- Go
- Rust

### Distributed judging
- worker nodes
- queue-based judge

### Real sandbox
- nsjail
- gVisor
- Firecracker

### Contest mode
- scoreboard
- freeze
- penalty

### AI-assisted debugging
- explain WA
- detect complexity issue
- suggest optimizations

---

# 19. Current Understanding Status

Hiện tại project context đã hiểu được:

- kiến trúc tổng thể
- AI pipeline
- sandbox flow
- database routing
- docker deployment
- async architecture
- testcase generation philosophy
- judge runtime

Document này dùng làm:
- persistent project context
- onboarding document
- future development reference
- AI memory bootstrap

---

# 20. Source References

Thông tin tổng hợp từ:

- setup.html
- project_architecture.md
- .gitignore

Key extracted facts:
- Spring Boot 3.2
- Java 21
- MySQL master/slave
- Gemini + OpenAI
- Docker deployment
- AI-generated generator architecture
- sandbox execution
- async job processing

---

# 21. Critical Insight

Điểm mạnh lớn nhất của PBJ Judge không phải chỉ là Online Judge.

Mà là:

## "AI-assisted scalable testcase generation pipeline"

Kiến trúc này cho phép:
- scale testcase generation
- reduce manual authoring
- auto-create stress tests
- auto-validate solutions
- reduce workload cho problem setters

Đây là phần giá trị nhất của toàn bộ hệ thống.

