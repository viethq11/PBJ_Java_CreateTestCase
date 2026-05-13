
Nếu đề bài thiếu hoặc mơ hồ ở các phần:

format input/output
constraints
guarantee
edge cases
hidden invariants
dữ liệu có âm không
graph connected không
tree hay graph thường
indexing từ 0 hay 1
multiple test hay single test

thì model gần như phải “đoán”.

Và khi model đoán:

generator sẽ sinh sai format
validator sẽ mismatch
golden solution có thể solve sai problem thật
testcase yếu vì không biết max constraints thật

Trong ảnh của bạn, model đã suy luận sai cấu trúc:

Expected 5 D values, but got 2

nghĩa là AI:

không hiểu chính xác format
hoặc format được mô tả quá thiếu
hoặc output schema không đủ cứng

Hiện tại pipeline của bạn đang phụ thuộc khá nhiều vào:

Natural Language Understanding

trong khi Online Judge production-level cần:

Formal Problem Specification

Đây là khác biệt cực lớn.

Vấn đề cốt lõi hiện tại

AI của bạn đang cố làm đồng thời:

1. Hiểu đề
2. Suy luận constraints
3. Suy luận input format
4. Suy luận edge cases
5. Viết generator
6. Viết validator
7. Viết AC solution
8. Sinh anti-test

Chỉ cần sai 1 bước là toàn pipeline hỏng.

Hướng đi đúng tiếp theo

Bạn cần chuyển từ:

AI generates everything from vague statement

sang:

AI first reconstructs a FORMAL SPEC

rồi mới generate artifacts.

Kiến trúc đúng cho PBJ Judge
STAGE 1 — Problem Reconstruction

AI KHÔNG generate testcase ngay.

AI chỉ làm:

{
  "input_format": "...",
  "output_format": "...",
  "constraints": {
    "N": "1 <= N <= 2e5",
    "Ai": "-1e9 <= Ai <= 1e9"
  },
  "multiple_testcases": false,
  "guarantees": [
    "graph connected",
    "tree",
    "no duplicate edges"
  ],
  "corner_cases": [
    "N=1",
    "all equal",
    "strictly increasing"
  ]
}

Nếu confidence thấp:

bắt AI trả "unknown"
KHÔNG generate generator
STAGE 2 — Schema Validation

Backend kiểm tra:

- Có input format chưa?
- Có constraints chưa?
- Có variable undefined không?
- Constraints có parse được không?

Nếu fail:

yêu cầu AI repair SPEC
chưa cho generate testcase
STAGE 3 — Generator Synthesis

Lúc này mới prompt:

Generate generator ONLY from THIS schema.
DO NOT infer new variables.
DO NOT invent constraints.
STAGE 4 — Self-Consistency Checks

Chạy:

generator
↓
validator
↓
golden solution
↓
WA/TLE probes

Nếu fail:

repair generator
KHÔNG regenerate toàn bộ problem
Tại sao test hiện tại yếu

Log này:

Testcases are weak:
AI-generated slow correct probe still gets AC

cho thấy AI không biết:

Constraint thật lớn tới đâu

nên nó chỉ sinh:

random nhỏ
random trung bình
không có adversarial pattern

Ví dụ bài cần:

O(N log N)

nhưng AI chỉ sinh:

N <= 1000

thì:

O(N^2) vẫn AC
Thứ bạn đang thiếu KHÔNG phải model mạnh hơn

Đây là điểm quan trọng nhất.

Model mạnh hơn chỉ cải thiện:

Natural language understanding

nhưng KHÔNG giải quyết triệt để:

underspecified problems

Ngay cả GPT-5/Gemini 2.5 Pro cũng sẽ hallucinate nếu đề thiếu constraints.

Thứ cần thêm vào pipeline
1. Formal Spec Layer (QUAN TRỌNG NHẤT)

Đây là bước bắt buộc.

2. Constraint Inference Engine

Ví dụ:

Nếu AI detect:

N up to 2e5

thì backend tự sinh:

anti_quadratic_tests = true
3. Deterministic Validator Generator

AI phải sinh:

bool validate(input)

Backend chạy validator trước khi lưu testcase.

4. Pattern-Based Adversarial Library

KHÔNG phụ thuộc AI hoàn toàn.

Ví dụ:

sorted
reverse sorted
all equal
star graph
chain graph
dense graph
duplicate-heavy
max frequency
overflow traps

Backend tự inject.

Kiến trúc production-level thực tế

Các OJ lớn không dùng:

AI → testcase trực tiếp

Mà là:

Problem Spec
→ handwritten validator
→ handwritten generator
→ stress testing
→ checker

AI chỉ nên:

hỗ trợ generate draft
hỗ trợ detect missing constraints
hỗ trợ tạo anti-patterns

KHÔNG nên là source of truth duy nhất.

Hiện tại dự án của bạn đã vượt qua giai đoạn “demo AI judge” rồi. Vấn đề bây giờ là chuyển sang:

Reliable Judge Infrastructure

và bước đầu tiên chính là:

Formal Problem Specification Layer