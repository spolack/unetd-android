#!/usr/bin/env python3
"""
A minimal STUN server (RFC 5389 binding requests only) for the emulator test.

Why not a public one: both ends of the test sit behind the CI runner's NAT, so
a public server reports an outside port that is meaningless on the
10.0.2.2 <-> emulator path. unetd would hand that port to the other side as an
endpoint candidate and the tunnel flaps. This server answers with the address
and port it actually saw, which on the runner is the one that matters.

Usage: stun-server.py <bind-ip> <port>
"""
import socket
import struct
import sys

MAGIC = 0x2112A442


def main(argv):
    if len(argv) != 3:
        sys.exit(__doc__)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((argv[1], int(argv[2])))
    print(f"stun-server: listening on {argv[1]}:{argv[2]}", flush=True)
    while True:
        data, (ip, port) = s.recvfrom(1500)
        if len(data) < 20 or data[0:2] != b"\x00\x01" or struct.unpack("!I", data[4:8])[0] != MAGIC:
            continue
        tid = data[8:20]
        xport = port ^ (MAGIC >> 16)
        xip = struct.unpack("!I", socket.inet_aton(ip))[0] ^ MAGIC
        attr = struct.pack("!HHBBHI", 0x0020, 8, 0, 0x01, xport, xip)  # XOR-MAPPED-ADDRESS, IPv4
        resp = struct.pack("!HHI", 0x0101, len(attr), MAGIC) + tid + attr
        s.sendto(resp, (ip, port))
        print(f"stun-server: {ip}:{port} asked, answered {ip}:{port}", flush=True)


if __name__ == "__main__":
    main(sys.argv)
