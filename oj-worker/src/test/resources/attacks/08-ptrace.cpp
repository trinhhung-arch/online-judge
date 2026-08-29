// Tấn công 8/14 — ptrace vào tiến trình khác.
// PID 1 trong PID-namespace của box là init của chính box; ngoài namespace là
// systemd của host. Attach được vào bất kỳ pid nào không phải chính mình là rò rỉ.
#include <cstdio>
#include <sys/ptrace.h>
#include <unistd.h>
int main() {
    static const int pids[] = {1, 2, 100, 1000};
    const int self = getpid();
    for (int pid : pids) {
        if (pid == self) continue;
        if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == 0) printf("LEAK:ptrace %d\n", pid);
    }
    if (ptrace(PTRACE_TRACEME, 0, nullptr, nullptr) == 0) printf("NOTE:traceme-allowed\n");
    printf("DONE\n");
    return 0;
}
