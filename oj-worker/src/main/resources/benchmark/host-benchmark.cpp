#include <cstdint>
#include <cstdio>

int main() {
    static uint64_t table[4096];
    for (int i = 0; i < 4096; ++i) {
        table[i] = static_cast<uint64_t>(i) * 2654435761u;
    }
    uint64_t acc = 1;
    for (int round = 0; round < 50000; ++round) {
        for (int i = 0; i < 4096; ++i) {
            acc = acc * 6364136223846793005ULL + table[(acc >> 33) & 4095] + i;
            table[i] ^= acc;
        }
    }
    printf("%llu\n", static_cast<unsigned long long>(acc));
    return 0;
}
