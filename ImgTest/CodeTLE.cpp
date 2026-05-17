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

        set<long long> cows;
        for (int i = 0; i < N; i++) {
            long long x;
            cin >> x;
            cows.insert(x);
        }

        int turn = 0; // 0 = Hieu, 1 = RR

        while (true) {
            long long pos = *cows.rbegin();

            long long target = -1;
            for (long long i = pos - 1; i >= 1; i--) {
                if (!cows.count(i)) {
                    target = i;
                    break;
                }
            }

            if (target == -1) {
                break;
            }

            cows.erase(pos);
            cows.insert(target);

            turn ^= 1;
        }

        if (turn == 1)
            cout << "Hieu\n";
        else
            cout << "RR\n";
    }

    return 0;
}