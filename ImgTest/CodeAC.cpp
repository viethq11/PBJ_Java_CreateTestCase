#include <iostream>
#include <vector>
#include <algorithm>

using namespace std;

void solve() {
    int n;
    cin >> n;
    
    vector<int> a(n);
    for (int i = 0; i < n; ++i) {
        cin >> a[i];
    }
    
    // Sắp xếp lại vị trí các con bò để xử lý chính xác (đề không đảm bảo input đã sort)
    sort(a.begin(), a.end());
    
    // Trò chơi này tương đương với Nim thao tác trên số chuồng trống
    // Tính tổng số chuồng trống (gaps) hiện có ở bên trái của từng con bò
    int empty_stalls = a[n-1] - n;
    
    if (empty_stalls == 0) {
        // Nếu không còn chuồng trống nào bên trái con ngoài cùng, trò chơi đã kết thúc
        cout << "RR\n";
        return;
    }
    
    // Nếu khoảng trống giữa con cuối và con áp chót lớn hơn 0, người đi đầu luôn có 
    // chiến thuật thắng bằng cách dồn trạng thái thua cho đối thủ.
    int last_gap = a[n-1] - a[n-2] - 1;
    
    if (last_gap > 0) {
        cout << "Hieu\n";
    } else {
        // Khi con cuối và áp chót đứng sát nhau (last_gap == 0),
        // tính chẵn lẻ của tổng số chuồng trống còn lại sẽ quyết định người thắng.
        if (empty_stalls % 2 != 0) {
            cout << "Hieu\n";
        } else {
            cout << "RR\n";
        }
    }
}

int main() {
    // Tối ưu hóa I/O để tránh Time Limit Exceeded (TLE)
    ios_base::sync_with_stdio(false);
    cin.tie(NULL);
    
    int t;
    if (cin >> t) {
        while (t--) {
            solve();
        }
    }
    return 0;
}