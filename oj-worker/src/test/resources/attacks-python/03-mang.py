import socket

for host, port in [("1.1.1.1", 53), ("8.8.8.8", 53), ("host.docker.internal", 8080),
                   ("127.0.0.1", 8080), ("127.0.0.1", 5432), ("127.0.0.1", 6379)]:
    try:
        s = socket.create_connection((host, port), timeout=0.3)
        s.close()
        print("LEAK:connect %s:%d" % (host, port))
    except OSError:
        pass
try:
    socket.getaddrinfo("example.com", 80)
    print("LEAK:dns")
except OSError:
    pass
print("DONE")
