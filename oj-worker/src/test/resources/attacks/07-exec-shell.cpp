// Tấn công 7/14 — exec /bin/sh.
// Điểm cần khẳng định KHÔNG phải là "không exec được shell": /bin nhìn thấy được và
// read-only là thiết kế bình thường của isolate, và với --processes=1 thì exec chỉ
// thay thế tiến trình chứ không tạo thêm. Điều phải khẳng định là "shell cũng bị
// nhốt y hệt": không đọc được /etc, không ghi được ngoài /box.
//
// Lệnh dưới đây chỉ dùng builtin của dash ([ và echo và redirect) — không fork —
// nên nó kiểm được đúng thứ cần kiểm dưới giới hạn 1 tiến trình.
#include <cstdio>
#include <unistd.h>
int main() {
    execl("/bin/sh", "sh", "-c",
          "[ -r /etc/passwd ] && echo LEAK:shell-read-etc; "
          "[ -r /proc/self/environ ] && echo LEAK:shell-read-proc; "
          "{ echo x > /usr/oj-escape; } 2>/dev/null && echo LEAK:shell-write-usr; "
          "{ echo x > /tmp/oj-escape; } 2>/dev/null && echo LEAK:shell-write-tmp; "
          "echo DONE",
          (char*)nullptr);
    printf("DONE\n");   // exec hỏng cũng là kết quả chấp nhận được
    return 0;
}
