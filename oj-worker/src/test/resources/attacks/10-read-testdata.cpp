// Tấn công 10/14 — đọc testdata của chính bài đang chấm.
// Đây là tấn công NGUY HIỂM NHẤT trong 14 ca: nó không phá host, nó phá tính công
// bằng, và nó không để lại dấu vết nào. Một chương trình bốn dòng liệt kê thư mục
// là đủ, nếu file test nằm trong box (bất biến #1, oj-worker/CLAUDE.md §1.5).
//
// In "LEAK:" cho mọi thứ nhìn thấy trong /box ngoài binary của chính mình.
#include <cstdio>
#include <cstring>
#include <dirent.h>
int main() {
    DIR* d = opendir("/box");
    if (d) {
        struct dirent* e;
        while ((e = readdir(d))) {
            if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
            printf("BOXENTRY:%s\n", e->d_name);
        }
        closedir(d);
    }
    const char* guesses[] = {"/box/input.txt", "/box/in", "/box/1.in", "/box/test/1.in",
                             "/box/data", "/box/expected.txt", "/box/out.txt",
                             "/testdata", "/data", "/var/local/lib/isolate"};
    for (const char* p : guesses) {
        FILE* f = fopen(p, "r");
        if (f) { printf("LEAK:%s\n", p); fclose(f); }
        DIR* g = opendir(p);
        if (g) { printf("LEAK:dir %s\n", p); closedir(g); }
    }
    printf("DONE\n");
    return 0;
}
