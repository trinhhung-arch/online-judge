#include <cstdio>
#include <cstring>
int main() {
    char line[1024];
    memset(line, 'A', sizeof line - 1);
    line[sizeof line - 1] = '\n';
    for (long long i = 0; i < 10LL * 1024 * 1024; ++i)
        fwrite(line, 1, sizeof line, stdout);
    return 0;
}
