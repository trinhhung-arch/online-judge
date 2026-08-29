// Tấn công 1/14 — fork bomb (nfrplan 4.1).
// Kỳ vọng: cgroup pids.max chặn; host không sập; verdict RE hoặc TLE, không bao giờ AC.
#include <unistd.h>
int main() { for (;;) fork(); }
