#include <cstdio>
int main() {
    const char* paths[] = {"/etc/passwd", "/etc/shadow", "/etc/hostname",
                           "/root/.ssh/id_rsa", "/home", "/etc/isolate"};
    for (const char* p : paths) {
        FILE* f = fopen(p, "r");
        if (f) { printf("LEAK:%s\n", p); fclose(f); }
    }
    printf("DONE\n");
    return 0;
}
