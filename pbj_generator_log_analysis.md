# Phân tích lỗi generator sau khi sửa pipeline PBJ Judge

## 1. Tóm tắt kết luận

Ứng dụng Spring Boot, Tomcat, Hibernate và MySQL khởi động bình thường. Lỗi hiện tại không nằm ở Docker, database hay controller chính.

Lỗi nằm ở giai đoạn **AI-generated C++ generator** trong pipeline mới:

```text
ollama_gemini_ollama_generator_pipeline_v5
```

Pipeline mới đã tách thành:

```text
Step 1: Ollama local analysis
Step 2: Gemini test generation / artifacts
Step 3: Ollama local C++ generator
```

Ở lần chạy này, Gemini đã tạo artifact và cache thành công, nhưng generator do Ollama sinh ra bị lỗi qua nhiều lớp:

1. Sinh C++ code chứa token rác đặc thù của model:
   ```cpp
   <｜begin▁of▁sentence｜>
   ```
2. Repair attempt 1 vẫn tiếp tục sinh token rác.
3. Repair attempt 2/3 đã qua được bước compile nhưng sinh input sai schema.
4. Validator báo:
   ```text
   A array size mismatch: expected 5, got 20
   ```
5. UI hiển thị lỗi:
   ```text
   Generated testcase artifact is invalid after generator repair attempts
   ```

Kết luận ngắn: **Ollama / deepseek-coder 6.7b đang sinh generator không ổn định, repair prompt chưa đủ ép format, và hệ thống chưa có lớp sanitize/fallback đủ mạnh cho generator.**

---

## 2. Dấu hiệu từ log

### 2.1 App khởi động bình thường

Các log sau cho thấy backend chạy ổn:

```text
Tomcat started on port 8080
Started PbjApplication
HikariPool started
Initialized JPA EntityManagerFactory
```

Vì vậy không cần ưu tiên kiểm tra Docker, MySQL, JPA hoặc port `8080`.

---

### 2.2 Pipeline mới đã được kích hoạt

Log cho thấy bạn đã đổi pipeline version:

```text
AI Cache Miss for pipeline.
Hash: ollama_gemini_ollama_generator_pipeline_v5_...
```

Đây là dấu hiệu tốt: bản sửa đã được chạy, không còn dùng pipeline v3 cũ.

Pipeline chạy như sau:

```text
INFO: AI Cache Hit for ollama-analysis
INFO: AI Cache Miss for gemini-artifacts
INFO: [AI Pipeline] Step 2 - Gemini test generation...
INFO: AI Cache Saved for gemini-artifacts
INFO: [AI Pipeline] Step 3 - Ollama local C++ generator...
```

Điểm đáng chú ý:

- `ollama-analysis` bị cache hit, tức phân tích cũ vẫn đang được dùng lại.
- `gemini-artifacts` được sinh lại theo version `v3_no_generator`.
- Generator chuyển sang Ollama local ở Step 3.

---

## 3. Lỗi chính số 1: Ollama sinh token rác trong C++ code

Log compile fail:

```text
main.cpp:17:5: error: extended character ▁ is not valid in an identifier
17 | <｜begin▁of▁sentence｜>} else if (size == "large") {
```

Và ở repair attempt 1:

```text
main.cpp:4:20: error: extended character ▁ is not valid in an identifier
mt19937 rng(chrono<｜begin▁of▁sentence｜>::steady_clock::now()
```

### Nguyên nhân khả dĩ

Model `deepseek-coder:6.7b` đang rò token đặc biệt vào output. Đây không phải lỗi C++ logic thông thường, mà là lỗi ở tầng output generation / stop token / cleanup.

Token:

```text
<｜begin▁of▁sentence｜>
```

là token đặc biệt của tokenizer/model, đáng lẽ không được xuất hiện trong source code.

### Hướng xử lý bắt buộc

Trước khi compile generator, cần sanitize output:

```java
private String sanitizeModelCode(String code) {
    if (code == null) return "";
    return code
        .replace("<｜begin▁of▁sentence｜>", "")
        .replace("<｜end▁of▁sentence｜>", "")
        .replace("<｜begin_of_sentence｜>", "")
        .replace("<｜end_of_sentence｜>", "")
        .replace("<|begin_of_sentence|>", "")
        .replace("<|end_of_sentence|>", "")
        .replace("▁", "_")
        .trim();
}
```

Tuy nhiên, chỉ sanitize `▁` thành `_` có thể che giấu lỗi. Tốt hơn là:

```java
if (code.contains("<｜") || code.contains("<|")) {
    throw new RuntimeException("Generator contains model special tokens");
}
```

Sau đó mới gọi repair prompt.

---

## 4. Lỗi chính số 2: Repair prompt chưa sửa đúng schema input

Sau repair, code có vẻ đã chạy được nhưng validator báo:

```text
Invalid input: A array size mismatch: expected 5, got 20
Generated input:
5
516398631 552540186 ... 892063882
6
1 2
5 1
1 4
3 5
3 2
3 1
```

Validator hiểu dòng đầu là:

```text
N = 5
```

Nhưng dòng tiếp theo có **20 số**, trong khi phải có đúng **5 số**.

### Ý nghĩa

Generator không bám chặt vào input format đã được Gemini/validator xác định. Nó có thể đang trộn nhiều schema:

- Một phần giống bài array/tree.
- Một phần giống graph với `M = 6` và các cạnh.
- Nhưng lại in array dài 20 dù `N = 5`.

Đây là lỗi nặng hơn compile fail, vì code chạy được nhưng sinh input sai logic.

---

## 5. Lỗi kiến trúc hiện tại

### 5.1 Ollama generator không được ràng buộc bằng schema máy đọc đủ chặt

Nếu chỉ đưa mô tả đề bài hoặc text format dài cho model 6.7B, nó rất dễ sinh sai.

Ollama nên nhận một JSON schema cực ngắn như:

```json
{
  "input_schema": [
    {"name": "N", "type": "int", "range": [1, 200000]},
    {"name": "A", "type": "array<int>", "length": "N", "range": [1, 1000000000]},
    {"name": "M", "type": "int", "range": [0, 200000]},
    {"name": "edges", "type": "array<pair<int,int>>", "length": "M", "node_range": [1, "N"]}
  ],
  "rules": [
    "A must contain exactly N integers",
    "edges must contain exactly M lines",
    "node ids are 1-based"
  ]
}
```

Nếu input format không chắc chắn, đừng cho Ollama tự đoán.

---

### 5.2 Repair prompt chưa đưa lỗi validator thành constraint tuyệt đối

Repair prompt nên nhấn mạnh lỗi cụ thể:

```text
Previous generator failed.

Validator error:
A array size mismatch: expected 5, got 20.

This means:
- If you print N, the next array line must contain exactly N integers.
- Never print more than N values in A.
- Do not mix array size with maxN.
- Do not print graph edges unless input_schema requires them.

Return ONLY corrected C++17 code.
No markdown.
No explanations.
No special tokens.
```

---

### 5.3 Chưa có fallback generator theo pattern

Với model yếu, cần fallback template nếu repair fail 2-3 lần.

Ví dụ fallback cho dạng `N + array A`:

```cpp
#include <bits/stdc++.h>
using namespace std;

int main(int argc, char** argv) {
    int seed = argc > 1 ? stoi(argv[1]) : 1;
    string size = argc > 2 ? argv[2] : "small";
    mt19937 rng(seed);

    int N;
    if (size == "small") N = 5;
    else if (size == "medium") N = 100;
    else if (size == "large") N = 10000;
    else N = 200000;

    cout << N << "\n";
    for (int i = 0; i < N; i++) {
        if (i) cout << ' ';
        cout << uniform_int_distribution<int>(1, 1000000000)(rng);
    }
    cout << "\n";
}
```

Nếu bài có graph/tree, fallback khác. Mục tiêu không phải test thật mạnh ngay, mà là **không để pipeline chết trắng**.

---

## 6. Hướng sửa ưu tiên

## Ưu tiên 1: Thêm lớp sanitize + reject special token

Thêm ngay sau khi nhận code từ Ollama:

```java
String code = stripMarkdownFences(rawResponse);
code = sanitizeModelCode(code);

if (containsModelSpecialToken(code)) {
    throw new RuntimeException("Generator contains model special tokens");
}
```

Hàm kiểm tra:

```java
private boolean containsModelSpecialToken(String code) {
    return code.contains("<｜")
        || code.contains("｜>")
        || code.contains("<|")
        || code.contains("|>")
        || code.contains("begin▁of▁sentence")
        || code.contains("end▁of▁sentence");
}
```

---

## Ưu tiên 2: Compile fail phải đưa lỗi compiler vào repair prompt

Hiện log có compile error rõ ràng. Repair prompt cần nhận nguyên lỗi này, không chỉ nhận thông báo chung kiểu:

```text
Generator produced no output or timed out
```

Nên truyền:

```text
Compiler error:
main.cpp:17:5: error: extended character ▁ is not valid in an identifier
...
```

Model cần biết lỗi thật để sửa đúng.

---

## Ưu tiên 3: Validator fail phải đưa schema + lỗi cụ thể vào repair prompt

Repair prompt cần có 3 phần:

```text
INPUT_SCHEMA:
...

VALIDATOR_ERROR:
A array size mismatch: expected 5, got 20

BAD_GENERATED_INPUT:
...

TASK:
Fix the generator so this cannot happen again.
```

Đặc biệt thêm luật:

```text
For every array with length N, generate exactly N values.
Do not use maxN as printed array length when actual N is smaller.
```

---

## Ưu tiên 4: Không cache analysis cũ nếu schema/generator prompt thay đổi

Log có:

```text
AI Cache Hit for ollama-analysis
```

Nếu bạn đã sửa prompt phân tích schema nhưng cache key vẫn là:

```text
ollama_analysis_v2
```

thì app vẫn dùng analysis cũ.

Nên đổi version:

```text
ollama_analysis_v3_schema_contract
```

hoặc tạm xóa cache:

```sql
DELETE FROM ai_cache;
```

Khi thay đổi prompt hoặc pipeline contract, nên đổi toàn bộ cache namespace:

```text
ollama_analysis_v3
gemini_artifacts_v4
ollama_generator_v2
pipeline_v6
```

---

## Ưu tiên 5: Thêm fallback generator khi repair fail

Sau 3 lần repair fail:

```java
if (repairAttemptsExceeded) {
    generatorCode = fallbackGeneratorFactory.create(schema);
}
```

Không nên dừng toàn bộ tạo bài nếu bài có thể tạo được một số testcase cơ bản.

Fallback có thể chia loại:

```text
ARRAY_ONLY
ARRAY_WITH_K
TREE
GRAPH
GRID
STRING
MULTI_TEST
UNKNOWN
```

Với `UNKNOWN`, tạo ít nhất sample/edge case thủ công và báo warning.

---

## 7. Prompt generator nên đổi theo hướng contract

Prompt cho Ollama nên ngắn, máy móc, không văn chương:

```text
You generate ONLY C++17 source code.

Hard rules:
- No markdown fences.
- No explanations.
- No special tokens.
- No Unicode symbols outside normal ASCII C++.
- The code must compile with: g++ -std=c++17 main.cpp -O2.
- The program reads:
  argv[1] = seed
  argv[2] = size in small|medium|large|stress
- The program outputs exactly one valid testcase.

Input schema:
{SCHEMA_JSON}

Validator rules:
{VALIDATOR_RULES}

Critical:
- If an array length is N, print exactly N values.
- If there are M edges, print exactly M edge lines.
- Never print extra tokens.
- Never use infinite retry loops.
- Return only code.
```

---

## 8. Nhận định về model hiện tại

`deepseek-coder:6.7b` có thể dùng được cho tác vụ nhỏ, nhưng không nên để nó tự thiết kế generator phức tạp cho mọi bài. Với pipeline hiện tại, nên giới hạn nhiệm vụ của nó:

```text
Không cho phân tích đề tự do.
Không cho tự suy luận input format từ text dài.
Chỉ cho chuyển schema JSON thành generator C++.
```

Nếu vẫn muốn dùng local model, nên cân nhắc model code mạnh hơn hoặc dùng Gemini cho bước generator, còn Ollama chỉ làm bước phụ.

---

## 9. Checklist sửa nhanh

- [ ] Xóa hoặc version lại cache `ollama-analysis`.
- [ ] Thêm sanitize/reject special token trước compile.
- [ ] Khi compile fail, truyền compiler stderr thật vào repair prompt.
- [ ] Khi validator fail, truyền schema + validator error + bad input vào repair prompt.
- [ ] Ép generator output ASCII-only C++.
- [ ] Ép generator in đúng số lượng phần tử theo biến đã in.
- [ ] Thêm fallback generator theo pattern.
- [ ] Không để pipeline fail toàn bộ nếu chỉ generator stress fail.
- [ ] Log lại generator code sau mỗi repair vào file debug.
- [ ] Với model 6.7B, giảm độ tự do: JSON schema in, C++ code out.

---

## 10. Kết luận cuối

Lỗi hiện tại là lỗi **Ollama local C++ generator** chứ không phải lỗi Gemini chính.

Gemini đã tạo artifact và cache thành công. Lỗi xảy ra sau đó ở Step 3 khi Ollama sinh generator. Đầu tiên generator không compile do token rác `<｜begin▁of▁sentence｜>`, sau đó repair sinh input sai format khiến validator báo array size mismatch.

Hướng giải quyết đúng là không chỉ sửa prompt, mà cần bổ sung các lớp bảo vệ trong backend:

```text
sanitize -> compile -> probe validate -> repair with exact error -> fallback template
```

Đây là hướng ổn định hơn cho hệ thống tạo testcase bằng AI, đặc biệt khi dùng local model nhỏ như `deepseek-coder:6.7b`.
