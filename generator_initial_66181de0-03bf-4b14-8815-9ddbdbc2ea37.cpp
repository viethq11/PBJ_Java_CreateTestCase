#include <bits/stdc++.h>
using namespace std;
int main(int argc, char** argv) {
    int seed = stoi(argv[1]);
    string size_level = argv[2];
    mt19937 rng(seed);
    
    // Choose constraints by size level
    int N = 0;
    int M = 0;
    if (size_level == "small") {
        N = 50;
        M = N * 2;
    } else if (size_level == "medium") {
        N = 1e3;
        M = N * 2;
   } else if (size_level == "large") {
        N = 1e4;
        M = 1e5;
    } else if (size_level == "stress") {
        N = 1e6;
        M = 1e7;
    }
    
    // Generate valid input
    uniform_int_distribution<int> dist(1, 1000000000);
    vector<int> D(N), P(M);
    for (auto& d : D) {
        d = dist(rng);
    }
    for (auto& p : P) {
        p = dist(rng);
    }
    
    // Print testcase
    cout << N << " " << M << "\n";
    for (const auto& d : D) {
        cout << d << " ";
    }
    cout << "\n";
    for (const auto& p : P) {
        cout << p << " ";
    }
    cout << "\n";
}