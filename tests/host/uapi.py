#!/usr/bin/env python3
"""Minimal wireguard cross-platform UAPI client: dump a device's state."""
import socket, sys

def get(sock_path):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(sock_path)
    s.sendall(b"get=1\n\n")
    buf = b""
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
        if buf.endswith(b"\n\n"):
            break
    s.close()
    return buf.decode()

if __name__ == "__main__":
    print(get(sys.argv[1]), end="")
