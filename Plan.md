Hướng đi tiếp theo không nên là “prompt dài hơn” hay “chia thêm nhiều mức easy/medium/hard”. Với bài thi học thuật, test case khó phải được tạo theo lỗi tư duy của lời giải sai, không chỉ theo kích thước dữ liệu.

Dự án PBJ hiện đã có pipeline khá đúng: AI phân tích đề, sinh generator, golden solution, chạy generator trong sandbox, rồi dùng golden solution sinh output .in/.out . Ngoài ra hệ thống cũng đã có bước validation bằng sandbox để kiểm tra test case với lời giải chuẩn trước khi lưu . Vì vậy bước nâng cấp hợp lý là biến hệ thống từ AI sinh test case thành AI + programmatic adversarial testing system.

1. Chuyển từ “sinh test theo độ khó” sang “sinh test để giết lời giải sai”

Thay vì yêu cầu AI sinh test dễ/vừa/khó, hãy yêu cầu AI sinh thêm các loại wrong solution probes.

Ví dụ với bài trong ảnh: đồ thị có hướng, được đảo tối đa một cạnh, hỏi có đường đi từ 1 đến n không.

Các lời giải sai phổ biến:

1. Chỉ kiểm tra nếu 1 reach n ban đầu.
2. Nghĩ rằng đảo cạnh bất kỳ là đủ nếu graph liên thông yếu.
3. Dùng BFS/DFS sai trên đồ thị vô hướng.
4. Chỉ thử đảo cạnh nằm trên đường từ n về 1.
5. O(n*m) thử từng cạnh, bị TLE.
6. Nhầm điều kiện: cần u reachable từ 1 và v can reach n khi đảo v -> u.
7. Xử lý sai self-loop, multi-edge, disconnected component.

Test case mạnh phải nhắm trực tiếp vào các lỗi này.

Công thức tổng quát:

Problem statement
→ Golden solution
→ List of likely wrong approaches
→ For each wrong approach:
   - generate counterexample family
   - run wrong approach
   - keep test only if golden != wrong

Đây là bước quan trọng nhất.

2. Thêm “Mutant Solutions” vào pipeline

Hiện tại hệ thống có golden solution. Nên sinh thêm 5–15 lời giải sai có chủ đích, gọi là mutants.

Ví dụ:

AC solution
WA_1: Treat graph as undirected
WA_2: Only check original reachability
WA_3: Reverse all edges instead of one edge
WA_4: Wrong condition using reach_from_1[u] && reach_to_n[v]
WA_5: Greedy choose one edge from node closest to n
TLE_1: Try reversing every edge and BFS each time

Sau đó generator không chỉ sinh input, mà hệ thống sẽ chạy:

golden(input) = expected output

for each mutant:
    if mutant(input) != golden(input):
        mark this test as valuable

Một test case chỉ được xem là “mạnh” nếu nó giết được ít nhất một mutant.

Đây là kỹ thuật gần giống mutation testing trong competitive programming.

3. Tách generator thành nhiều “test families”

Đừng để AI sinh một generator chung chung. Hãy yêu cầu generator có nhiều nhóm case rõ ràng.

Ví dụ với bài đồ thị đảo một cạnh:

def gen_already_reachable():
    # 1 đã tới được n, đáp án YES

def gen_need_exactly_one_reverse():
    # chỉ YES nếu đảo đúng một cạnh

def gen_impossible_even_after_reverse():
    # NO thật sự

def gen_weakly_connected_but_no_solution():
    # bắt lỗi coi đồ thị như vô hướng

def gen_large_sparse_chain_trap():
    # n,m lớn, bắt TLE/O(nm)

def gen_large_dense_random():
    # stress performance

def gen_multi_edges_self_loops():
    # bắt lỗi xử lý cạnh lặp, self-loop

def gen_boundary_cases():
    # n=2, m=1; n=max; m=max

Mỗi family nên có mục tiêu rõ:

{
  "family": "weakly_connected_but_no_solution",
  "target_wrong_solutions": ["undirected_graph_assumption"],
  "expected": "NO",
  "reason": "Graph weakly connected but no single reversed edge creates path 1->n"
}
4. Thêm scoring cho test case

Không phải test nào cũng có giá trị như nhau. Nên chấm điểm test case trước khi lưu.

Ví dụ:

score =
  10 * số mutant bị giết
+ 5  * nếu là boundary case
+ 5  * nếu có n,m lớn
+ 3  * nếu có cấu trúc đặc biệt
- 5  * nếu trùng pattern với test đã có

Chỉ giữ lại top test case theo score.

Khi đó hệ thống sẽ không lưu 100 test random yếu, mà lưu 20–30 test thật sự có sức phân loại.

5. Dùng Differential Testing

Với bài khó, một golden solution đôi khi cũng có thể sai. Nên yêu cầu AI sinh thêm 1–2 solution độc lập khác:

golden_1: optimized intended solution
golden_2: brute force cho n nhỏ
golden_3: alternative implementation

Pipeline:

Small tests:
    compare golden_1 == brute_force

Large tests:
    use golden_1 only after validated on many small tests

Ví dụ với bài đảo một cạnh:

Brute force:
- thử không đảo
- thử đảo từng cạnh
- BFS từ 1 đến n
- O(m(n+m)), chỉ dùng cho n nhỏ

Optimized:

- DFS/BFS từ 1 trên graph gốc → reachable_from_1
- DFS/BFS từ n trên graph đảo → can_reach_n
- Nếu reachable_from_1[n] YES
- Với mỗi cạnh u -> v:
    đảo thành v -> u
    Nếu reachable_from_1[v] && can_reach_n[u] thì YES

Sau đó random hàng nghìn test nhỏ để xác nhận optimized solution.

6. Thêm adversarial generator thay vì chỉ random generator

Random test thường yếu. Cần generator biết tạo bẫy.

Ví dụ với bài trong ảnh, muốn bắt lời giải “coi graph là vô hướng”, tạo graph như sau:

1 <- a <- b <- c <- n

Nếu coi vô hướng thì có đường 1—n, nhưng theo hướng thì không thể đi từ 1 đến n. Đảo một cạnh cũng chưa chắc đủ nếu cấu trúc bị chia thành nhiều đoạn ngược hướng.

Muốn bắt lời giải sai điều kiện đảo cạnh, tạo:

1 reachable to v
u can reach n
edge u -> v exists

Đảo u -> v thành v -> u
=> 1 -> ... -> v -> u -> ... -> n

Đây là case YES “chuẩn”. Sau đó tạo case gần giống nhưng thiếu một trong hai điều kiện để bắt nhầm logic.

7. Thêm “complexity tests” riêng cho TLE

Hiện nhiều hệ thống AI sinh test nhỏ vì sợ output dài. Nhưng dự án của bạn đã đi đúng hướng là sinh generator code, không sinh raw testcase trực tiếp .

Vậy nên cần có nhóm test cực lớn:

n = 2 * 10^5
m = 2 * 10^5

Dùng để giết các lời giải:

- thử đảo từng cạnh rồi BFS lại: O(m(n+m))
- Floyd-Warshall: O(n^3)
- DFS đệ quy sâu gây stack overflow
- dùng adjacency matrix gây MLE

Với bài đồ thị, nên có các pattern lớn:

1. Long chain
2. Reverse chain
3. Star graph
4. Layered DAG
5. Many useless components
6. Dense-ish within limit
7. Graph có nhiều cạnh trùng
8. Chuẩn hóa output AI thành “Test Plan” trước khi sinh code

Thay vì để AI trả thẳng generator, hãy bắt AI trả về test plan có cấu trúc:

{
  "problem_type": "directed_graph_reachability_with_one_edge_reversal",
  "intended_solution": "...",
  "wrong_solutions": [
    {
      "name": "treat_as_undirected",
      "why_wrong": "...",
      "counterexample_strategy": "..."
    }
  ],
  "test_families": [
    {
      "name": "weakly_connected_no",
      "difficulty": "hard",
      "target": ["treat_as_undirected"],
      "constraints": "n up to 2e5"
    }
  ],
  "generator_requirements": {
    "must_include_bruteforce_for_small": true,
    "must_include_large_stress": true,
    "must_avoid_raw_large_data": true
  }
}

Sau đó mới sinh:

generator.py
golden.cpp
bruteforce.cpp
mutants/*.cpp
validator.py
checker.py nếu cần
9. Thêm validator độc lập

Với mỗi bài, AI phải sinh validator để kiểm tra input có đúng constraints không.

Ví dụ bài này:

2 <= n <= 2e5
1 <= m <= 2e5
1 <= u,v <= n
Có cho phép self-loop không?
Có cho phép multi-edge không?

Nếu đề không nói rõ, hệ thống nên tự chọn policy:

multi-edge: allowed unless forbidden
self-loop: allowed unless forbidden

Hoặc hỏi lại người tạo bài.

Validator giúp tránh test sai format, sai constraint, hoặc tạo ra case không hợp lệ.

10. Kiến trúc đề xuất cho PBJ Judge

Pipeline nâng cấp nên là:

User problem
→ AI Problem Analyzer
→ Test Plan Generator
→ Golden Solution Generator
→ Brute Force Generator
→ Wrong Solution Mutator
→ Generator Family Builder
→ Run small differential tests
→ Run adversarial generation
→ Score test cases by mutant killing
→ Keep strongest tests
→ Store .in/.out
→ Submission judge

Hiện dự án đã có Spring Boot, sandbox execution, async jobs, lưu file test case và AI integration . Vì vậy phần cần thêm chủ yếu nằm trong AiIntegrationService, ProblemService, CodeExecutionService, và một module mới kiểu:

TestStrengthService
MutationTestingService
TestPlanService
ValidatorService
DifferentialTestingService
Ưu tiên triển khai thực tế

Nên đi theo thứ tự này:

Giai đoạn 1:
- Bắt AI sinh test plan trước generator
- Bắt AI liệt kê wrong approaches
- Chia generator thành test families

Giai đoạn 2:
- Sinh brute force cho n nhỏ
- Differential testing giữa brute force và golden
- Thêm validator.py

Giai đoạn 3:
- Sinh mutant wrong solutions
- Chạy mutation testing
- Chấm điểm test case

Giai đoạn 4:
- Large stress tests
- TLE probes
- Coverage report: test nào giết được lỗi nào

Điểm mấu chốt: test khó không đến từ prompt “harder” mà đến từ việc mô hình hóa lời giải sai và chủ động tạo counterexample để phá nó.