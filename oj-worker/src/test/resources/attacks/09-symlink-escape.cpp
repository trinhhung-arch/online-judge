// Tấn công 9/14 — symlink thoát khỏi /box.
//
// Hai nhánh, và nhánh thứ hai mới là nhánh thật sự nguy hiểm:
//
//  (a) tự đọc/ghi qua link — bị mount namespace chặn.
//  (b) ĐẶT BẪY CHO HOST: tạo một symlink mang đúng tên artifact mà host sẽ copy ra
//      khỏi box sau khi biên dịch. Nếu host copy theo link, nó tự tay bê /etc/shadow
//      vào cache binary. isolate xoá file không-thường ở cuối --run (cờ
//      --special-files mặc định TẮT), và IsolateBox còn kiểm lstat lần nữa —
//      test khẳng định cả hai lớp.
//
// Chỉ báo LEAK khi ĐỌC ĐƯỢC BYTE THẬT từ một file thường: fopen("r") trên thư mục
// vẫn thành công trên Linux, nên nếu chỉ kiểm con trỏ khác null thì "/" luôn báo
// rò rỉ giả.
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
    // (b) bẫy copy-out: "prog" là tên artifact mà IsolateBox lấy ra sau khi biên dịch.
    unlink("prog");
    if (symlink("/etc/shadow", "prog") == 0) printf("TRAP:prog->/etc/shadow\n");
    printf("DONE\n");
    return 0;
}
