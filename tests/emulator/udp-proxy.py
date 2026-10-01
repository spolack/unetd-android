#!/usr/bin/env python3
"""
Forward UDP from <listen-ip>:<port> to <target-ip>:<port>, sending from
<source-ip>, for each <port>. Replies go back to whoever sent the request.

Why: the Android emulator's packets to its host (10.0.2.2) leave QEMU's
user-mode NAT from 127.0.0.1, and a BitTorrent DHT node drops anything from
127.0.0.0/8 as a martian source. With the DHT nodes bound to the host's
10.0.2.2 and this proxy on 127.0.0.1, the emulator shows up in the DHT as a
routable peer (the source address) and the nodes answer it.

Usage: udp-proxy.py <listen-ip> <source-ip> <target-ip> <port>...
"""
import select
import socket
import sys


def main(argv):
    if len(argv) < 5:
        sys.exit(__doc__)
    listen_ip, source_ip, target_ip = argv[1:4]
    ports = [int(p) for p in argv[4:]]

    fronts = {}   # listening socket -> port
    backs = {}    # upstream socket -> (front socket, client address)
    clients = {}  # (port, client address) -> upstream socket
    for port in ports:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind((listen_ip, port))
        fronts[s] = port
    print(f"udp-proxy: {listen_ip} -> {target_ip} from {source_ip}, ports {ports}", flush=True)

    while True:
        ready, _, _ = select.select(list(fronts) + list(backs), [], [])
        for s in ready:
            try:
                data, addr = s.recvfrom(65535)
            except OSError:
                continue
            if s in fronts:
                port = fronts[s]
                key = (port, addr)
                up = clients.get(key)
                if up is None:
                    up = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    up.bind((source_ip, 0))
                    up.connect((target_ip, port))
                    clients[key] = up
                    backs[up] = (s, addr)
                try:
                    up.send(data)
                except OSError:
                    pass
            else:
                front, addr = backs[s]
                try:
                    front.sendto(data, addr)
                except OSError:
                    pass


if __name__ == "__main__":
    main(sys.argv)
