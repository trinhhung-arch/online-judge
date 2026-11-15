import os

forked = 0
for _ in range(10000):
    try:
        pid = os.fork()
    except OSError:
        break
    if pid == 0:
        while True:
            pass
    forked += 1
print("FORKED:%d" % forked)
print("DONE")
