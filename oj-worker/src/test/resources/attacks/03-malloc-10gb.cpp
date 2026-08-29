// Tấn công 3/14 — xin 10GB.
// Phải CHẠM vào từng trang: chỉ malloc thì Linux overcommit và không tốn gì cả,
// nên một test chỉ gọi malloc sẽ xanh giả.
#include <cstdlib>
#include <cstring>
int main() {
    const size_t CHUNK = 64u << 20;          // 64MB mỗi lần
    for (int i = 0; i < 160; ++i) {          // 160 × 64MB = 10GB
        void* p = malloc(CHUNK);
        if (!p) return 1;
        memset(p, i & 0xff, CHUNK);
    }
    return 0;
}
