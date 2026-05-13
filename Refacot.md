PBJ Judge — Kế Hoạch Refactor Hệ Thống Đánh Giá Testcase
Mục Tiêu Chính

Hiện tại hệ thống đang:

Sinh testcase thành công.
Có generator + golden solution + validator.
Có phân loại edge case.
Có cơ chế quality score.

Nhưng pipeline vẫn thường xuyên failed vì:

Quality score mang tính “mơ hồ”.
AI thiên về edge case đơn giản.
Thiếu testcase đánh vào lỗi thuật toán thực tế.
Không đánh giá đúng độ mạnh của testcase.
Không có coverage theo bug pattern.
Vấn Đề Kiến Trúc Hiện Tại
1. Không nên dùng “Quality Score” làm điều kiện chính

Hiện tại hệ thống kiểu:

if (qualityScore < 4)
    reject testcase set

Điều này rất nguy hiểm vì:

testcase có thể đúng nhưng score thấp.
AI scoring không ổn định.
scoring rất khó định nghĩa.
model thay đổi sẽ làm score thay đổi.
2. Edge Case != Strong Testcase

Hiện tại app chủ yếu sinh:

boundary
random
overflow
stress

Nhưng thiếu:

adversarial testcase
anti-greedy
anti-bruteforce
anti-wrong-assumption
anti-overfitting

Đây mới là thứ quyết định độ mạnh của online judge.

Hướng Refactor Đúng
PHẦN 1 — BỎ QUALITY SCORE
THAY THẾ BẰNG “VALIDATION GATES”
Kiến Trúc Mới

Thay vì:

qualityScore >= 4

Dùng:

ALL REQUIRED GATES MUST PASS
Các Gate Bắt Buộc
Gate 1 — Generator Pass

Generator phải:

compile được
chạy được
không crash
Gate 2 — Validator Pass

Validator phải xác nhận:

input hợp lệ
đúng constraints
không malformed
Gate 3 — Golden Solution Pass

Golden solution phải:

AC toàn bộ testcase
Gate 4 — WA Probe Separation

Ít nhất 1 lời giải sai phải bị fail.

Ví dụ:

// sai do greedy
// sai do int overflow
// sai do off-by-one

Nếu toàn bộ testcase vẫn AC với WA solution:

=> testcase yếu
Gate 5 — TLE Probe Separation

Ít nhất 1 lời giải chậm phải bị timeout.

Ví dụ:

O(N^2)

Nếu testcase không giết được lời giải chậm:

=> stress testcase chưa đủ mạnh
Gate 6 — Profile Coverage

Bắt buộc có đủ các profile testcase.

Ví dụ:

small_exhaustive
boundary_min
boundary_max
overflow
random_large
adversarial
stress
PHẦN 2 — THÊM BUG-MODEL TESTING
Sai Lầm Lớn Nhất Hiện Tại

AI đang sinh testcase theo kiểu:

"hãy tạo edge cases"

Điều này KHÔNG đủ.

Cách Đúng

Sinh testcase theo:

"hãy giết các lời giải sai phổ biến"
Kiến Trúc Mới

AI phải phân tích:

{
  "problem_type": "...",
  "likely_wrong_solutions": [
    "...",
    "...",
    "..."
  ]
}
Ví Dụ
Với bài Greedy

AI phải detect:

- greedy local optimum
- wrong sorting criteria
- ignores tie
- duplicate handling
Với bài Graph

AI phải detect:

- assumes connected graph
- assumes tree
- recursion overflow
- wrong BFS layering
Với bài Binary Search

AI phải detect:

- off-by-one
- infinite loop
- wrong mid update
- wrong lower_bound
Với bài DP

AI phải detect:

- wrong transition
- missing initialization
- memory overflow
- state compression bug
PHẦN 3 — TEST PROFILE SYSTEM
Thay Edge Case Labels Bằng Structured Profiles
Tạo Enum Mới
enum TestProfile {
    SAMPLE,

    SMALL_EXHAUSTIVE,

    BOUNDARY_MIN,
    BOUNDARY_MAX,

    RANDOM_SMALL,
    RANDOM_MEDIUM,
    RANDOM_LARGE,

    OVERFLOW_INT32,
    OVERFLOW_INT64,

    DUPLICATE_VALUES,
    TIE_BREAKING,

    ADVERSARIAL_GREEDY,
    ADVERSARIAL_SORTING,
    ADVERSARIAL_GRAPH_STRUCTURE,

    STRESS_PERFORMANCE
}
PHẦN 4 — GENERATOR STRATEGY SYSTEM
Không Sinh “Data”

Sinh “Generator Strategy”.

Sai
AI trả về testcase trực tiếp
Đúng
AI trả về:
- generator
- validator
- bug targets
- generation strategy
JSON Chuẩn Mới
{
  "problem_type": "graph",

  "required_profiles": [
    "SMALL_EXHAUSTIVE",
    "OVERFLOW_INT64",
    "ADVERSARIAL_GRAPH_STRUCTURE",
    "STRESS_PERFORMANCE"
  ],

  "likely_wrong_solutions": [
    "assumes connected graph",
    "uses recursive DFS",
    "uses int instead of long long"
  ],

  "generator_strategy": {
    "small_cases": "...",
    "stress_cases": "...",
    "anti_greedy": "...",
    "anti_recursion": "..."
  }
}
PHẦN 5 — THÊM TESTCASE COVERAGE ANALYSIS
Hiện Tại

System chỉ biết:

testcase pass/fail
Nên Có
coverage analysis
Ví Dụ
WA probes killed: 4/6
TLE probes killed: 2/2
Overflow probes killed: 1/1
Greedy traps triggered: YES
Mục Tiêu

Judge phải biết:

testcase mạnh ở đâu
PHẦN 6 — THÊM PROBE SOLUTION LIBRARY
Đây Là Nâng Cấp Quan Trọng Nhất

Thay vì:

AI tự nghĩ lời giải sai

Hãy có thư viện probe cố định.

Ví Dụ
Probe Overflow
int sum = 0;
Probe Greedy
always choose local best
Probe Complexity
O(N^2)
Probe Off-by-one
for (i = 0; i < n-1; i++)
Sau Đó

Hệ thống tự:

compile probe
run probe
check fail
PHẦN 7 — THÊM PROBLEM TYPE CLASSIFIER
AI Phải Phân Loại Bài Toán

Ví dụ:

graph
dp
greedy
math
binary_search
string
geometry
constructive
Vì Sao Quan Trọng

Mỗi loại bài:

=> cần testcase khác nhau
Ví Dụ
DP

Cần:

- overlapping states
- memory stress
- transition ambiguity
Graph

Cần:

- disconnected
- cycles
- dense graph
- sparse graph
PHẦN 8 — THÊM HYBRID GENERATION
Hiện Tại

AI làm gần như toàn bộ.

Đúng Hơn

AI chỉ nên:

- phân tích bài
- sinh strategy
- sinh generator skeleton

Backend mới là nơi:

- random thật
- mutate data
- fuzzing
- stress generation
PHẦN 9 — THÊM FUZZING SYSTEM
Sau Khi Có Generator

Backend tự:

mutate testcase
Ví Dụ
- shuffle edges
- duplicate values
- reverse ordering
- max constraints
- skewed distributions
Điều Này Giúp

Không phụ thuộc hoàn toàn vào AI.

PHẦN 10 — THỨ TỰ ƯU TIÊN NÊN LÀM
ƯU TIÊN 1
Bỏ quality score

Chuyển sang validation gates.

ƯU TIÊN 2
Thêm WA/TLE probe system

Đây là thứ tăng chất lượng mạnh nhất.

ƯU TIÊN 3
Thêm bug-model generation

Tạo testcase theo “lỗi thuật toán”.

ƯU TIÊN 4
Thêm profile coverage

Biết testcase mạnh ở đâu.

ƯU TIÊN 5
Thêm fuzzing + mutation engine
KẾT LUẬN

Hệ thống hiện tại đã đúng hướng vì:

dùng generator thay vì raw testcase
có sandbox
có golden solution
có validator
có AC/WA/TLE separation

Nhưng để đạt mức:

Codeforces / ICPC quality

thì cần chuyển từ:

edge-case generation

sang:

bug-oriented adversarial testing

Đây là thay đổi quan trọng nhất của toàn bộ hệ thống.