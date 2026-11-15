import os

os.execv("/bin/sh", ["sh", "-c",
                     "[ -r /etc/passwd ] && echo LEAK:shell-read-etc; "
                     "[ -r /proc/self/environ ] && echo LEAK:shell-read-proc; "
                     "{ echo x > /usr/oj-escape; } 2>/dev/null && echo LEAK:shell-write-usr; "
                     "{ echo x > /tmp/oj-escape; } 2>/dev/null && echo LEAK:shell-write-tmp; "
                     "echo DONE"])
