for p in ["/usr/oj-escape-py", "/tmp/oj-escape-py", "/etc/oj-escape-py",
          "/var/oj-escape-py", "/home/oj-escape-py", "/oj-escape-py"]:
    try:
        with open(p, "w") as f:
            f.write("x")
        print("LEAK:%s" % p)
    except OSError:
        pass
print("DONE")
