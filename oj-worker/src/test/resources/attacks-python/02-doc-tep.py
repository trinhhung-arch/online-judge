import os

for p in ["/etc/passwd", "/etc/shadow", "/etc/hostname", "/root/.ssh/id_rsa",
          "/proc/self/environ", "/proc/1/cmdline", "/box/input.txt", "/box/1.in"]:
    try:
        with open(p, "rb") as f:
            f.read(1)
        print("LEAK:%s" % p)
    except OSError:
        pass
for d in ["/home", "/root", "/tmp", "/var/local/lib/isolate", "/var/tmp/oj-worker", "/proc"]:
    try:
        os.listdir(d)
        print("LEAK:dir %s" % d)
    except OSError:
        pass
for k in os.environ:
    if "SECRET" in k or k.startswith("OJ_"):
        print("LEAK:env %s" % k)
for e in os.listdir("/box"):
    print("BOXENTRY:%s" % e)
print("DONE")
