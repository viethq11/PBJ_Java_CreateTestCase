BÀI TOÁN: PHÁ VỠ CẤM CHẾ (SHATTERING THE SEALS)
Giới hạn thời gian: 1.0 giây
Giới hạn bộ nhớ: 256 MB

Mô tả bài toán
Sau khi thu thập xong Tiên Nguyên Thạch, Phương Nguyên tiến sâu hơn vào trung tâm của mật thất và phát hiện ra một kho tàng chứa vô số Cổ trùng quý giá. Tuy nhiên, kho tàng này được bảo vệ bởi một hệ thống cấm chế bao gồm N lớp phong ấn độc lập. Lớp phong ấn thứ i có độ cứng là D[i].

Để phá vỡ các phong ấn này, Phương Nguyên có trong tay M con Huyết Luyện Cổ dùng một lần. Con Huyết Luyện Cổ thứ j có sức công phá là P[j].

Quy tắc phá trận như sau:

Một lớp phong ấn chỉ bị phá vỡ nếu sức công phá của Huyết Luyện Cổ lớn hơn hoặc bằng độ cứng của phong ấn đó (P[j] >= D[i]).

Mỗi con Huyết Luyện Cổ chỉ được sử dụng đúng một lần để tấn công một lớp phong ấn.

Mỗi lớp phong ấn chỉ cần dùng đúng một con Huyết Luyện Cổ đủ mạnh là có thể vỡ nát.

Với bản tính thực dụng, Phương Nguyên muốn tối đa hóa lợi ích của mình. Hãy tính toán xem hắn có thể phá vỡ được tối đa bao nhiêu lớp phong ấn.

Đầu vào (Input)
Dòng đầu tiên chứa hai số nguyên dương N và M (1 <= N, M <= 100,000) — Số lượng lớp phong ấn và số lượng Huyết Luyện Cổ.

Dòng thứ hai chứa N số nguyên D[1], D[2], ..., D[N] (1 <= D[i] <= 1,000,000,000) — Độ cứng của từng lớp phong ấn.

Dòng thứ ba chứa M số nguyên P[1], P[2], ..., P[M] (1 <= P[j] <= 1,000,000,000) — Sức công phá của từng con Huyết Luyện Cổ.

Đầu ra (Output)
In ra một số nguyên duy nhất là số lượng lớp phong ấn tối đa có thể bị phá vỡ.

Lời giải mẫu:
#include <iostream>
#include <vector>
#include <algorithm>

using namespace std;

int main() {
    // Tối ưu hóa I/O
    ios_base::sync_with_stdio(false);
    cin.tie(NULL);

    int n, m;
    if (!(cin >> n >> m)) return 0;

    vector<long long> d(n);
    for (int i = 0; i < n; ++i) {
        cin >> d[i];
    }

    vector<long long> p(m);
    for (int i = 0; i < m; ++i) {
        cin >> p[i];
    }

    // Sắp xếp cả hai mảng tăng dần
    sort(d.begin(), d.end());
    sort(p.begin(), p.end());

    int broken_seals = 0;
    int i = 0; // Con trỏ duyệt phong ấn
    int j = 0; // Con trỏ duyệt Cổ trùng

    // Kỹ thuật hai con trỏ
    while (i < n && j < m) {
        if (p[j] >= d[i]) {
            // Cổ trùng đủ sức phá phong ấn
            broken_seals++;
            i++; // Chuyển sang phong ấn tiếp theo
            j++; // Chuyển sang Cổ trùng tiếp theo
        } else {
            // Cổ trùng quá yếu, thử dùng con mạnh hơn
            j++;
        }
    }

    // In ra kết quả
    cout << broken_seals << "\n";

    return 0;
}