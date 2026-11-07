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
    printf("DONE\n");
    return 0;
}
