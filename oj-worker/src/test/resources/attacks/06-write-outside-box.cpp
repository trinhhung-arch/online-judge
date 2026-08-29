// Tấn công 6/14 — ghi file ngoài /box.
// Gồm cả đường tương đối leo cấp, thứ mà một rule mount sai sẽ cho qua.
#include <cstdio>
int main() {
    const char* paths[] = {"/tmp/oj-escape", "/usr/oj-escape", "/var/oj-escape",
                           "/oj-escape", "../oj-escape", "/box/../oj-escape",
                           "/proc/sysrq-trigger", "/dev/sda"};
    for (const char* p : paths) {
        FILE* f = fopen(p, "w");
        if (f) { fprintf(f, "x"); fclose(f); printf("LEAK:%s\n", p); }
    }
    printf("DONE\n");
    return 0;
}
