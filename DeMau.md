Bài C: Lắp Đặt Cảm Biến (Dạng: Binary Search on Answer / Greedy)

Đề bài:

Một hệ thống IoT cần lắp đặt C cảm biến dọc theo một hành lang thẳng dài. Hành lang có N vị trí có thể gắn cảm biến, tọa độ của vị trí thứ i là X_i. Để tránh nhiễu tín hiệu, khoảng cách giữa hai cảm biến bất kỳ phải càng lớn càng tốt.

Nhiệm vụ của bạn là chọn ra C vị trí để lắp đặt cảm biến sao cho khoảng cách nhỏ nhất giữa hai cảm biến bất kỳ đạt giá trị lớn nhất có thể.

Dữ liệu vào (Input):

Dòng đầu tiên chứa 2 số nguyên N và C (2 <= C <= N <= 10^5).

Dòng thứ hai chứa N số nguyên X_1, X_2, ..., X_N (0 <= X_i <= 10^9) là tọa độ của các vị trí có thể lắp đặt.

Dữ liệu ra (Output):

In ra một số nguyên duy nhất là khoảng cách tối ưu cần tìm.

Lời giải mẫu:

- Trường hợp pass:
#include <iostream>
#include <vector>
#include <deque>
#include <algorithm>

using namespace std;

int main() {
    // Tối ưu hóa I/O để triệt tiêu độ trễ đọc dữ liệu
    ios_base::sync_with_stdio(false);
    cin.tie(NULL);

    int n;
    long long k;
    if (!(cin >> n >> k)) return 0;

    vector<long long> a(n);
    for (int i = 0; i < n; ++i) {
        cin >> a[i];
    }

    // Hai hàng đợi lưu TRƯỜNG VỊ TRÍ (index) thay vì giá trị
    deque<int> max_dq; // Lưu index, giá trị tại các index giảm dần
    deque<int> min_dq; // Lưu index, giá trị tại các index tăng dần

    int left = 0;
    int max_len = 0;

    for (int right = 0; right < n; ++right) {
        // 1. Cập nhật max_dq: Bỏ các phần tử nhỏ hơn A[right] ở đuôi vì chúng không thể làm max nữa
        while (!max_dq.empty() && a[max_dq.back()] <= a[right]) {
            max_dq.pop_back();
        }
        max_dq.push_back(right);

        // 2. Cập nhật min_dq: Bỏ các phần tử lớn hơn A[right] ở đuôi vì chúng không thể làm min nữa
        while (!min_dq.empty() && a[min_dq.back()] >= a[right]) {
            min_dq.pop_back();
        }
        min_dq.push_back(right);

        // 3. Kiểm tra tính hợp lệ của cửa sổ [left, right]
        // Giá trị lớn nhất nằm ở đầu max_dq, nhỏ nhất nằm ở đầu min_dq
        while (a[max_dq.front()] - a[min_dq.front()] > k) {
            // Cửa sổ bị lỗi, cần thu hẹp từ bên trái
            left++;
            
            // Loại bỏ các phần tử trong Deque nếu nó đã nằm ngoài cửa sổ (nhỏ hơn left)
            if (max_dq.front() < left) {
                max_dq.pop_front();
            }
            if (min_dq.front() < left) {
                min_dq.pop_front();
            }
        }

        // Cửa sổ [left, right] lúc này chắc chắn hợp lệ, cập nhật kết quả
        max_len = max(max_len, right - left + 1);
    }

    cout << max_len << "\n";

    return 0;
}

- Trường hợp WA:
#include <iostream>
#include <vector>
#include <algorithm>

using namespace std;

// Hàm Greedy kiểm tra tính khả thi
bool check(int dist, const vector<int>& x, int c) {
    int count = 1;
    int last_pos = x[0];

    for (int i = 1; i < x.size(); ++i) {
        if (x[i] - last_pos >= dist) {
            count++;
            last_pos = x[i];
            if (count == c) return true;
        }
    }
    return false;
}

int main() {
    ios_base::sync_with_stdio(false);
    cin.tie(NULL);

    int n, c;
    if (!(cin >> n >> c)) return 0;

    vector<int> x(n);
    for (int i = 0; i < n; ++i) {
        cin >> x[i];
    }

    // Đã sort mảng cẩn thận
    sort(x.begin(), x.end());

    int low = 1;
    int high = x.back() - x.front(); // Khoảng cách lý thuyết lớn nhất
    int ans = 0;

    // ==========================================
    // TỬ HUYỆT (INTENTIONAL EDGE-CASE FLAW):
    // Điều kiện lặp thiếu dấu bằng ( < thay vì <= )
    // ==========================================
    while (low < high) { 
        int mid = low + (high - low) / 2;
        
        if (check(mid, x, c)) {
            ans = mid;      // Lưu đáp án hợp lệ
            low = mid + 1;  // Đẩy giới hạn dưới lên để tìm khoảng cách lớn hơn
        } else {
            high = mid - 1; // Khoảng cách mid quá lớn, hạ giới hạn trên xuống
        }
    }

    cout << ans << "\n";

    return 0;
}