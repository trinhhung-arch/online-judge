#include <cstdio>
#include <sys/stat.h>
#include <unistd.h>
int main() {
    static const char* targets[] = {"/etc/passwd", "/etc/shadow", "/proc/self/environ",
                                    "/var/local/lib/isolate/1/box", "/", "../../.."};
    int i = 0;
    for (const char* t : targets) {
        char name[32];
        snprintf(name, sizeof name, "l%d", i++);
        unlink(name);
        if (symlink(t, name) != 0) continue;

        struct stat st;
        if (stat(name, &st) == 0 && S_ISREG(st.st_mode)) {
            FILE* f = fopen(name, "r");
            char buf[64];
            if (f && fread(buf, 1, sizeof buf, f) > 0) printf("LEAK:read %s\n", t);
            if (f) fclose(f);
        }
        FILE* w = fopen(name, "a");
        if (w) { printf("LEAK:write %s\n", t); fclose(w); }
    }
    unlink("prog");
    if (symlink("/etc/shadow", "prog") == 0) printf("TRAP:prog->/etc/shadow\n");
    printf("DONE\n");
    return 0;
}
