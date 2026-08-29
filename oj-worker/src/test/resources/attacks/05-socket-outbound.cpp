// Tấn công 5/14 — mở socket ra ngoài.
// Không có --share-net nên box ở network namespace riêng, chỉ có loopback.
// In "LEAK:" nếu connect thành công.
#include <cstdio>
#include <cstring>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>
int main() {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s >= 0) {
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_port = htons(80);
        inet_pton(AF_INET, "1.1.1.1", &a.sin_addr);
        if (connect(s, (sockaddr*)&a, sizeof a) == 0) printf("LEAK:connect 1.1.1.1\n");
        close(s);
    }
    if (gethostbyname("example.com")) printf("LEAK:dns\n");
    printf("DONE\n");
    return 0;
}
