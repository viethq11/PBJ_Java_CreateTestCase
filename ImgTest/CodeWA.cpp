#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);

    int T;
    cin >> T;

    while (T--) {
        int N;
        cin >> N;

        vector<long long> a(N);
        long long sum = 0;

        for (int i = 0; i < N; i++) {
            cin >> a[i];
            sum += a[i];
        }

        // Sai cố ý:
        // Giả sử người thắng chỉ phụ thuộc vào tổng số bước cần dồn về 1..N
        long long target = 1LL * N * (N + 1) / 2;
        long long moves = sum - target;

        if (moves % 2 == 1)
            cout << "Hieu\n";
        else
            cout << "RR\n";
    }

    return 0;
}