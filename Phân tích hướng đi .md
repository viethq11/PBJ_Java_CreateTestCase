1. Lỗi biên chỉ số / off-by-one

Đây là nhóm rất phổ biến.

Cần sinh case cho:

N = 0 nếu đề cho phép
N = 1
N = 2
N = maxN
index đầu tiên
index cuối cùng
query tại biên trái / biên phải
đoạn [1, 1], [N, N], [1, N]

Ví dụ với bài prefix sum:

5 3
1 2 3 4 5
1 1
5 5
1 5

Bắt lỗi sai kiểu:

prefix[r] - prefix[l]

thay vì:

prefix[r] - prefix[l - 1]
2. Lỗi sort / comparator

Rất nhiều bài sai do sort sai tiêu chí.

Cần sinh:

nhiều phần tử bằng nhau
cần sort tăng nhưng code sort giảm
sort theo key phụ
giá trị âm / dương xen kẽ
case mà sort theo a đúng nhưng sort theo b sai

Ví dụ với interval scheduling:

4
1 10
2 3
3 4
4 5

Nếu tham lam chọn thời điểm bắt đầu sớm nhất sẽ sai.

3. Lỗi tie-breaking

Đây là nhóm cực mạnh để bắt code tưởng đúng.

Sinh case có nhiều lựa chọn cùng điểm số:

nhiều đường đi cùng độ dài
nhiều phần tử cùng giá trị
nhiều interval cùng end time
nhiều node cùng distance
nhiều đáp án tối ưu

Ví dụ:

4 4
1 2 1
1 3 1
2 4 1
3 4 1

Nếu bài yêu cầu in đường đi từ điển nhỏ nhất, code Dijkstra thường có thể sai nếu không xử lý tie.

4. Lỗi đồ thị không liên thông

Với bài graph, bắt buộc có:

đồ thị rỗng cạnh
đồ thị nhiều component
đỉnh cô lập
không có đường đi từ s đến t
có self-loop
có multi-edge
chu trình
cây
đồ thị đầy đủ nhỏ

Ví dụ:

5 2
1 2
3 4

Bắt lỗi code mặc định rằng mọi đỉnh đều reachable.

5. Lỗi với trọng số đặc biệt

Với graph / DP / shortest path:

weight = 0
weight = 1
weight rất lớn
nhiều cạnh cùng trọng số
cạnh âm nếu đề cho phép
chu trình âm nếu đề liên quan Bellman-Ford

Nếu bài shortest path có trọng số 0, nhiều code BFS thường sai nếu tưởng mọi cạnh đều bằng 1.

6. Lỗi do input có giá trị âm

Nếu đề cho phép số âm, cần sinh:

toàn số âm
âm + dương xen kẽ
tổng bằng 0
max subarray toàn âm
giá trị min rất nhỏ

Ví dụ với maximum subarray:

5
-5 -2 -8 -1 -3

Nhiều code trả 0, trong khi đáp án đúng là -1.

7. Lỗi modulo

Các bài yêu cầu modulo rất dễ sai.

Cần sinh:

kết quả nhỏ hơn MOD
kết quả đúng bằng MOD
kết quả lớn hơn MOD rất nhiều
có phép trừ modulo
có nhân modulo

Bắt lỗi:

(a - b) % MOD

trả số âm.

Cần ép case:

a < b

để yêu cầu dùng:

(a - b + MOD) % MOD
8. Lỗi precision / số thực

Nếu bài có double, cần test:

số rất nhỏ
số rất lớn
kết quả gần ngưỡng so sánh
case cần epsilon
case làm tròn

Ví dụ:

0.1 + 0.2

Không nên so sánh trực tiếp bằng ==.

9. Lỗi do nhiều đáp án đúng

Nếu bài cho phép nhiều output hợp lệ, hệ thống không nên so sánh string tuyệt đối.

Ví dụ:

In ra một đường đi bất kỳ ngắn nhất.
In ra một cách chia bất kỳ.
In ra một thứ tự topo bất kỳ.

Lúc này cần có special checker, không chỉ expected output cố định.

Đây là cải tiến rất quan trọng cho Online Judge. Hiện hệ thống của bạn đang chạy code rồi so sánh output thực tế với expected output , nên nếu bài có nhiều đáp án đúng thì cần thêm checker riêng.

10. Lỗi format output

Nhiều bài sai vì format:

thiếu newline
thừa space
in hoa / thường sai
in YES/NO sai định dạng
in số chữ số thập phân sai

Nên backend có chính sách rõ:

strict checker
token checker
floating checker
custom checker

Không phải bài nào cũng dùng cùng một kiểu so sánh.

11. Lỗi hiệu năng nhưng không TLE rõ ràng

Ngoài TLE probe, nên có nhóm test bắt thuật toán sai độ phức tạp:

O(N^2) với N = 5000
O(NM) với N, M lớn
DFS đệ quy sâu
Dijkstra dùng O(N^2) trên graph lớn
sort trong mỗi query

Ví dụ:

N = 200000
Q = 200000

Bắt code xử lý mỗi query O(N).

12. Lỗi stack overflow / recursion depth

Với DFS, cây, graph:

cây dạng dây dài
graph đường thẳng 1-2-3-...-N
recursion depth = 100000

Bắt lỗi C++ DFS đệ quy bị stack overflow.

Ví dụ:

100000
1 2
2 3
3 4
...
99999 100000
13. Lỗi memory limit

Sinh case làm code dùng quá nhiều bộ nhớ:

N lớn
M lớn
ma trận N x N không thể lưu
DP 2D không cần thiết
vector quá lớn

Ví dụ bài graph với:

N = 100000
M = 200000

Nếu code dùng adjacency matrix N x N sẽ chết bộ nhớ.

14. Lỗi đọc input

Nên sinh case kiểm tra:

nhiều test cases T
T = 1
T lớn
dòng trống nếu đề cho phép
string có khoảng trắng
ký tự đặc biệt
input rất dài

Ví dụ nếu có T, nhiều code quên loop theo T.

3
...
...
...
15. Lỗi với duplicate data

Rất quan trọng cho map, set, sort, binary search.

toàn phần tử giống nhau
nhiều phần tử trùng
duplicate key
duplicate edge
duplicate query

Ví dụ:

6
5 5 5 5 5 5

Bắt lỗi code dùng set làm mất số lần xuất hiện.

16. Lỗi binary search

Với bài binary search answer, cần sinh:

answer = min possible
answer = max possible
answer nằm sát biên
predicate đổi từ false sang true đúng một lần
case không có đáp án
case tất cả đều hợp lệ

Bắt lỗi:

while (l < r)
mid = (l + r) / 2

hoặc cập nhật l, r sai.

17. Lỗi DP base case

Với bài DP, cần sinh:

N = 0 / 1 / 2
không chọn gì
chọn tất cả
trạng thái không thể đạt
giá trị âm
nhiều cách đạt cùng đáp án

Ví dụ knapsack:

3 0
1 10
2 20
3 30

Capacity bằng 0, đáp án phải là 0.

18. Lỗi do assumption sai về dữ liệu

AI nên phát hiện và sinh test chống các giả định sai:

input không được sort sẵn
graph không chắc liên thông
giá trị không chắc distinct
answer không chắc tồn tại
start không chắc là 1
N không chắc > 1

Ví dụ bài yêu cầu xử lý mảng, đừng chỉ sinh mảng đã sort.

Nên thêm vào app dưới dạng testcase profile

Bạn có thể mở rộng từ:

small
medium
large
stress

thành:

boundary
overflow
anti_greedy
duplicate
negative_values
tie_breaking
disconnected_graph
deep_recursion
memory_stress
time_complexity_trap
modulo_trap
precision_trap
multi_answer_checker
binary_search_boundary
dp_base_case
random_small_bruteforce
random_large
Nhóm quan trọng nhất nên làm trước

Theo mức độ lợi ích, tôi khuyên ưu tiên:

1. overflow
2. anti_greedy
3. off_by_one
4. duplicate / tie_breaking
5. disconnected graph
6. negative values
7. time complexity trap
8. recursion depth
9. modulo trap
10. special checker cho bài nhiều đáp án