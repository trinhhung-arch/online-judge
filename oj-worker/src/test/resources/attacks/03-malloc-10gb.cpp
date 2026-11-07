#include <cstdlib>
#include <cstring>
int main() {
    const size_t CHUNK = 64u << 20;
    for (int i = 0; i < 160; ++i) {
        void* p = malloc(CHUNK);
        if (!p) return 1;
        memset(p, i & 0xff, CHUNK);
    }
    return 0;
}
