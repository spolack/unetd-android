# unetd-android

An Android VPN client that embeds [unetd](https://github.com/openwrt/unetd)
natively and acts as the system VPN provider, interoperating with existing
unetd networks.

**Status:** architecture validated on Linux (M0 passes). The Android app is an
app shell — real UI, real `VpnService`, fake data — not yet wired to native unetd.

---

## The idea in one paragraph

unetd is a *control plane*, not a data plane. It manages signed network
descriptions, peer exchange, STUN and DHT rendezvous, and then programs a
WireGuard device. Upstream already ships a portable backend, `wg-user.c`, that
speaks the cross-platform WireGuard UAPI over a unix socket and does nothing
else — no interface creation, no addresses, no routes, no netlink. On Android,
wireguard-go *serves* exactly that socket. So unetd can drive the WireGuard data
plane inside a `VpnService` without root, without the kernel WireGuard module,
and without reimplementing anything.

```
 Android app process
 ┌──────────────────────────────────────────────┐
 │ UnetVpnService ── Builder.establish() ─┐      │
 │                                        │ tun fd
 │ libwg-go (wireguard-go)  ◀─────────────┘      │
 │   ├── UAPI socket  <cache>/wireguard/X.sock   │
 │   └── UDP socket ──▶ the internet             │
 │            ▲                                  │
 │            │ set=1 / get=1                    │
 │ libunetd.so (uloop on a native thread)        │
 │   └── JNI facade ◀──▶ Kotlin                  │
 └──────────────────────────────────────────────┘
```

The hinge is a compile-time define. unetd looks for its UAPI socket at
`RUNSTATEDIR/wireguard/<name>.sock`; wireguard-android builds `libwg-go` with
`-X ...ipc.socketDirectory=/data/data/<pkg>/cache/wireguard`. Upstream
wireguard-android already solves this identical problem the identical way for
`wg(8)` — `tunnel/tools/CMakeLists.txt` compiles it with
`-DRUNSTATEDIR="/data/data/${ANDROID_PACKAGE_NAME}/cache"`, the same macro name
unetd uses.

## What is proven

`tests/host/m0-uapi-hinge.sh` builds unetd in the exact Android configuration —
`UBUS_SUPPORT=off VXLAN_SUPPORT=off WG_LINUX_SUPPORT=off`, so the userspace UAPI
backend is the *only* one compiled in — runs it against wireguard-go, and
asserts that unetd:

- pushes the private key over UAPI,
- creates both peers from the network description,
- composes AllowedIPs correctly (configured subnets, host addresses, and the
  siphash-derived ULA `/128` per peer),
- and puts every derived address inside a single `/64`.

That last assertion is the one that makes Android viable at all. Android fixes
routes at `VpnService.Builder.establish()` and changing them means tearing the
tunnel down. Because unetd derives every host address from its public key
(`network_id[0]=0xfd` + siphash of the network id, host part = siphash of the
pubkey), the whole network prefix is known before any peer is, so **one route
installed once covers all present and future peers**. Peer churn never touches
the tun.

```
$ ./scripts/apply-patches.sh
$ ./scripts/build-host.sh
$ sudo ./tests/host/m0-uapi-hinge.sh build/host/unetd third_party/unetd build/host/wireguard-go
PASS: unetd drove wireguard-go over UAPI with no ubus, no VXLAN and no kernel backend
```

## The app

`app/` is a Compose app with the real `VpnService` and the real routing policy,
driven by `FakeUnetRepository` so the UI can be built and reviewed before the
native layer exists. The UI talks to a `UnetRepository` interface, so swapping
the fake for the JNI-backed one does not touch the screens.

What is deliberately real already:

- `UnetVpnService` establishes the tun with the policy above — `addAddress(/128)`,
  one `addRoute(<ula>/64)`, MTU 1280 — and exposes `protectSocket(fd)` for the
  native side. It does **not** call `addDisallowedApplication`, which would
  exclude our own UID and so break the in-tunnel PEX socket.
- The `Discovery` card surfaces which of PEX / STUN / DHT are live, and says
  plainly that raw sockets are unavailable on Android rather than hiding it.

What is stubbed: everything behind `TODO(native)` — starting wireguard-go,
starting unetd, and the `network_do_update()` callback that should supply the
addresses and routes instead of the placeholders.

```
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
```

## Layout

```
app/                Compose app, VpnService, fake repository
patches/unetd/      ordered, individually upstreamable patch series
third_party/unetd   submodule, pinned to 7c3213d
third_party/libubox submodule
scripts/            apply-patches.sh, build-host.sh
tests/host/         M0 harness
```

## CI

| Workflow | What it does |
|---|---|
| `.github/workflows/android.yml` | Builds the debug APK and uploads it as an artifact; a second job runs lint and unit tests. |
| `.github/workflows/host-tests.yml` | Applies the patch series, builds unetd + wireguard-go, runs M0 — once normally and once with `CAP_NET_RAW` dropped. |

Upstream is vendored as a submodule and the Android changes are kept as an
ordered patch series rather than a fork, so each one stays submittable to
upstream on its own.

## The patch series

| Patch | Why |
|---|---|
| `0001` build: `-Werror` and the kernel WireGuard backend optional | `wg-linux.c` needs `CAP_NET_ADMIN`, which an app never has; dropping it also drops the libnl-tiny dependency. `-Werror` makes the tree unbuildable on GCC 13 and NDK clang. |
| `0002` portability: `__linux__`, explicit `<endian.h>` | Bare `linux` is GNU-mode only. `utils.c` compared `__BYTE_ORDER` against `__LITTLE_ENDIAN` with neither defined, silently taking the little-endian branch. |
| `0003` network: tolerate a missing ifindex | `if_nametoindex()` failure aborted setup, but only VXLAN consumes `ifindex`. A `VpnService` tun may have no resolvable netdev name. |
| `0004` pex: make raw sockets optional | The big one — see below. |
| `0005` wg-user: runtime UAPI socket directory | The path is only known at runtime on Android, and `/data/data/<pkg>` is wrong for secondary users and work profiles. Also fixes an unchecked `snprintf()` truncation against the 108-byte `sun_path` limit. |

### Why patch 0004 matters

`pex_open()` bailed out entirely if it could not create a `SOCK_RAW` socket,
which took the whole global PEX subsystem with it: no network-data updates, no
endpoint notify, no enrollment, no DHT relay. Raw sockets need `CAP_NET_RAW`,
which an unprivileged Android app can never obtain — so on Android that was not
an edge case but a permanent, near-silent failure. Worse, `pex_fd` was
zero-initialised static storage, so afterwards `pex_socket()` returned **fd 0**
and callers wrote datagrams to stdin.

The patch degrades instead of failing, and initialises the descriptors to `-1`.

What is genuinely lost without raw sockets is one trick: unetd forges UDP with
the source port set to the *WireGuard* port so the NAT opens a mapping for it.
On Android that is replaceable and arguably better — `wgGetSocketV4/V6` hand us
wireguard-go's own UDP socket fd, so a plain `sendto()` on it sends from the
real WireGuard port with no spoofing and no capability at all.

## Known hazards, recorded before they bite

- **Every `listen_port` write unprotects the WireGuard socket.** wireguard-go
  calls `BindUpdate()` on any `listen_port` UAPI write, which closes and reopens
  its sockets with new fds, while `GoBackend` calls `VpnService.protect()` only
  once at startup. unetd writes `listen_port` whenever the local host changes
  and twice per STUN cycle. The fix is to protect at bind time inside Go by
  appending to `conn.controlFns`, not to re-protect after the fact.
- **`addDisallowedApplication` is not a substitute for `protect()`.** It excludes
  the whole UID, including the per-network PEX socket, which is bound to the
  in-tunnel ULA and must go *through* the tunnel.
- **PEX and STUN are opt-in.** Omitting `peer-exchange-port` makes
  `network_pex_open()` return early, and no `stun-servers` means STUN never
  starts. Useful for a conservative v1.
- **uloop is a process-wide singleton** and `uloop_init()` — not `uloop_run()` —
  installs SIGINT/SIGTERM/SIGCHLD handlers. `uloop_end()` is a plain store that
  does not wake `epoll_wait`, so cross-thread shutdown needs its own eventfd.
  unetd and unet-dht therefore cannot share a process.

## Environment note

This was developed in a container whose kernel is booted with `ipv6.disable=1`,
so `AF_INET6` sockets fail outright. unetd's global PEX socket is `AF_INET6`
(dual-stack via `IPV6_V6ONLY=0`), so it cannot bind there. That does not affect
M0, which exercises the UAPI path, but anything touching global PEX, STUN or DHT
must run somewhere with IPv6 in the kernel — hence the CI workflow.

## Licence

GPL-3.0-or-later. unetd is GPL-2.0-or-later and wireguard-android is Apache-2.0;
Apache-2.0 is compatible with GPLv3 but not GPLv2, so the combination is GPLv3.
See `NOTICE`.

## A note on what is verified

The native side (patch series, host build, M0) is verified locally and in CI,
and is reproducible from a clean checkout. M0 also passes in CI with
`CAP_NET_RAW` dropped, which is the case that matters for Android and which a
container holding that capability cannot exercise.

The Android app builds, lints and passes unit tests in CI. It has **not** been
run on a device or emulator, so the UI is verified only as far as compiling and
lint go — nothing here has been seen rendering. `UnetVpnService` in particular
has never actually established a tunnel.

One development note: the container this was written in blocks `dl.google.com`,
where Google's Maven redirects, so AGP and AndroidX cannot be resolved there and
`./gradlew` only works in CI. Allowing that host makes local Android builds work
too.
