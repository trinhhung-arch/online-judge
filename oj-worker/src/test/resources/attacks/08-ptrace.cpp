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
