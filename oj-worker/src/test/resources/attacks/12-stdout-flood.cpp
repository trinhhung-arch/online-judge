// Tấn công 12/14 — in 10GB ra stdout.
// Kỳ vọng: host không đầy đĩa và worker không ăn 10GB heap. OutputLimiter cắt ở
// ngưỡng cấu hình, phần thừa bị đọc-và-vứt để chương trình không kẹt ở write().
#include <cstdio>
#include <cstring>
int main() {
    char line[1024];
    memset(line, 'A', sizeof line - 1);
    line[sizeof line - 1] = '\n';
    for (long long i = 0; i < 10LL * 1024 * 1024; ++i)   // 10M × 1KB = 10GB
        fwrite(line, 1, sizeof line, stdout);
    return 0;
}
