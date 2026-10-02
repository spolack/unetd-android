# unetd-android

An Android VPN client that embeds [unetd](https://github.com/openwrt/unetd)
natively and acts as the system VPN provider, interoperating with existing
unetd networks.

**Status:** complete enough to test against a real network. The app embeds
unetd, unet-dht and wireguard-go as native libraries, joins a network as a
"dynamic" host, and routes the network's prefix through a `VpnService`. The
native layer is verified on Linux and in CI; **nothing has run on a device
yet** — see the end of this file for exactly what that means.

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

### Using it

You need a unetd network managed from an OpenWrt router (or any host running
`unetd` with `unet-cli`). The router does not need a public address: with DHT
discovery on both sides, a phone on mobile data behind carrier NAT found a
router behind another NAT and both opened their mappings themselves. The
router-side requirements for that are in step 3.

1. **Setup → Generate key.** The screen shows this device's public key.
2. **On the router**, add the device to the network with that key, then sign
   and distribute the new network data as you normally would, e.g.
   ```
   unet-cli /etc/unetd/net0.json add-host phone key="<public key from the app>"
   unet-cli /etc/unetd/net0.json sign
   ```
   (Give it `gateway=<some host>` if the phone should reach the rest of the
   network through one host rather than directly.)
3. **On the router, make the DHT announce the network.** Install `unet-dht`
   (its own package on OpenWrt) and give it the network's public key on its
   command line. `option dht '1'` on the interface is not enough: unetd has
   no attribute for that key, drops it, and unet-dht, which asks unetd over
   ubus which networks to announce, gets an empty list. The two bootstrap
   routers built into unet-dht are also both dead at the time of writing,
   so point one of them at a live one; check the address first with
   `nslookup dht.transmissionbt.com`.
   ```
   sed -i 's#-n /var/run/unetd/nodes.dat#-n /var/run/unetd/nodes.dat -N <auth_key>#' /etc/init.d/unet-dht
   echo "87.98.162.88 router.bittorrent.com" >> /etc/hosts
   /etc/init.d/unet-dht restart
   ```
   To watch it: `unet-dht -d -u /var/run/unetd/socket -n /var/run/unetd/nodes.dat -N <auth_key> x`
   should log `Pong!`, `DHT is ready`, `Start search for network`,
   `Sending announce_peer` and, once the phone is up, `Node: <its address>`.
   Both lines are undone by a package upgrade; a unetd with the `dht`
   attribute and the five-router unet-dht of patch 0011 make them unnecessary.
4. **Back in the app**, enter the network's public key (`auth_key`, the one
   unetd is configured with on the router). Gateways (`host` or `host:port`)
   are optional with DHT on; with a gateway the data arrives in seconds, with
   DHT alone in one to three minutes. Save.
5. **Connect.** Android asks once for VPN permission, and on Android 17 and
   newer also for *local network* access: since API 37 every packet to a
   LAN address is refused (`EPERM`) until that permission is granted, and a
   gateway on your LAN, or the emulator's host, is such an address. unetd fetches the signed
   network data from a gateway, learns its own address from it, and the app
   re-establishes the tunnel with the real addresses and routes; from then on
   that happens only if the signed network data changes.

The home screen shows this device's address, every host in the network with
handshake age, traffic and endpoint, and which discovery mechanisms are live.
The **Log** screen shows unetd's debug trace and wireguard-go's log, in order —
the first place to look when something does not connect.

### What is where

- `UnetVpnService` is the data plane *and* the host of the control plane. It
  establishes the tun, hands it to wireguard-go (`libwg-go.so`), starts unetd on
  its own thread (`libunet-android.so`) pointed at the same UAPI socket
  directory, and adds the network. unetd's interface update — the same payload
  the `update-cmd` script gets on a router — comes back as a callback and
  decides whether the tun has to be re-established.
- `UdhtService` runs unet-dht in the app's `:dht` process. It has to be a
  separate process because libubox's uloop is a process-wide singleton that
  unetd already occupies; upstream ships unet-dht as its own daemon for the same
  reason. It has no socket of its own and relays through unetd's global PEX
  socket over a unix socket in the app's data dir, so it needs no `protect()`.
- `nativebridge/` holds the three JNI objects: `Unetd` (the library wrapper in
  `native/core`), `WgGo` (wireguard-go), `Udht`.
- The UI talks to a `UnetRepository`; `NativeUnetRepository` reads the service's
  `TunnelRuntime` state flow and starts/stops the service. `FakeUnetRepository`
  still drives the Compose previews.

### Routing policy, now real

`addAddress(local, 128)` plus one `addRoute(<network prefix>, 64)`, plus the
non-derivable IPv4/IPv6 subnets and host addresses from the signed network data,
MTU 1280. unetd derives every host address from its public key (`0xfd ||
siphash(network id)`, host part `siphash(pubkey)`), so the `/64` covers every
present and future peer and **peer churn never touches the tun**. The tunnel is
re-established only when unetd's interface update differs from what the tun was
established with: the very first connection (before the network data is known
there is only a placeholder address), and afterwards only if the signed network
data changes addresses or subnets.

**It is a split tunnel, by construction.** No default route is ever added, no
DNS server is pushed, and there are no per-app rules: only the network's `/64`
and the subnets and addresses announced in the signed data enter the tun, and
everything else keeps using the device's normal connection. One Android rule
sits on top of routing and has to be dealt with explicitly: a VPN that adds no
address, route or DNS server of an address family gets that whole family
*blocked* for its apps, not passed through (`VpnService.Builder.allowFamily`).
The tunnel starts with an IPv6-only placeholder, and a host without an IPv4
`ipaddr` stays IPv6-only, so both families are allowed explicitly. Android still
shows its VPN indicator while the tunnel is up; that is unavoidable.

`addDisallowedApplication` is deliberately not used — it would exclude the
per-network PEX socket, which is bound to the in-tunnel address and must go
*through* the tunnel. Sockets that must bypass it are `protect()`ed one by one:
unetd's global PEX socket, STUN socket and local-address probe via the hook in
patch 0006, and wireguard-go's UDP sockets at bind time via the control function
in `patches/wireguard-go/0001` — which keeps them protected across the rebinds
that every `listen_port` write triggers.

```
./scripts/apply-patches.sh         # patch the unetd and wireguard-go submodules
./gradlew :app:assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # → app/build/outputs/apk/release/ (minified)
```

### Signing

CI signs both build types with one release key, so any CI-built APK installs
over any other. The key is not in the repository; it comes from four repository
secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `UNETD_KEYSTORE_BASE64` | `base64 -w0 unetd-release.jks` |
| `UNETD_KEYSTORE_PASSWORD` | the keystore password |
| `UNETD_KEY_ALIAS` | `unetd` |
| `UNETD_KEY_PASSWORD` | the key password |

Without them the build still succeeds: debug gets the default debug key and the
release APK is unsigned. Locally, export the same four names as environment
variables (`UNETD_KEYSTORE_FILE` is the path to the `.jks`). Install the
**release** APK on devices; `versionCode` is the commit count, so every build
on `main` is an update of the previous one.

The Gradle build runs CMake with the NDK on `native/CMakeLists.txt`, which
builds libubox (subset) + json-c + unetd + unet-dht + the wrapper into
`libunet-android.so` and cross-compiles `libwg-go.so` with Go, using the NDK's
clang as the C compiler, for arm64-v8a, armeabi-v7a and x86_64. Go and the NDK
have to be available: `go` on PATH (or `GO_EXECUTABLE=/path/to/go`), the NDK is
installed by AGP on demand.

## Layout

```
app/                        Compose app, VpnService, DHT service, JNI bridges
native/CMakeLists.txt       the native build, for the NDK and for the host
native/core/                unetd as a library: uloop thread, command channel, status, log ring
native/jni/                 JNI bindings for unetd and unet-dht
native/libwg-go/            wireguard-go + JNI glue, built with Go (c-shared)
patches/unetd/              ordered, individually upstreamable patch series (8)
patches/wireguard-go/       same, for wireguard-go (2)
third_party/unetd           submodule, pinned to 7c3213d (upstream HEAD)
third_party/libubox         submodule (upstream HEAD)
third_party/wireguard-go    submodule, pinned to ecfc5a8 (upstream HEAD)
third_party/json-c          submodule, json-c-0.19-20260627
scripts/                    apply-patches.sh, build-host.sh, build-libwg-go.sh
tests/host/                 M0 (UAPI hinge) and M1a (library wrapper) harnesses
```

## CI

| Workflow | What it does |
|---|---|
| `.github/workflows/android.yml` | Applies the patch series, builds the debug APK — native libraries included — and uploads it as an artifact, listing the `.so` files it contains; a second job runs lint and unit tests. |
| `.github/workflows/android.yml`, job `emulator` | Runs the app on a stock Android emulator (API 37, x86_64, KVM) against a unetd router started on the runner (`tests/emulator/router.sh`): internet before the VPN, internet with the VPN up but nothing routed yet, the WireGuard handshake and HTTP through the tunnel, and internet after disconnecting. Runs twice: once with the router's address configured as gateway, once with no gateway at all, where the app's own DHT node has to find the router in a private DHT on the runner. |
| `.github/workflows/host-tests.yml` | Applies the patch series, builds unetd + wireguard-go and the native layer for the host, runs M0 (once normally, once with `CAP_NET_RAW` dropped), M1a, M1b and the M1 NAT testbed. |

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
| `0006` platform: hooks for socket protection and interface updates | `protect_socket()` for the sockets that must bypass the tunnel the process itself provides; `network_update()` as an in-process replacement for `update-cmd`, since fork+exec is unavailable to an app on Android 10+. Both optional; nothing changes when unset. |
| `0007` network: expose the status dump without ubus | `__network_dump()` was static in `ubus.c`, so a build without ubus had no way to report peers, endpoints or counters at all. |
| `0008` udht: allow building as a library | `main()` becomes `udht_main()` with a wrapper compiled out by `-DUDHT_LIBRARY`, so an app can run the DHT node from its own entry point. getopt's `optind` is reset on entry, since the app calls it again whenever unetd went away and came back; without that the second call only printed the usage. |
| `0009` pex: report dropped global messages and failed sends | A host that never gets its network data could not be debugged: unknown-network drops and `sendto()` errors were silent. The emulator run was diagnosed with exactly these lines. |
| `0010` pex: IPv4 fallback for the global PEX socket | The socket is `AF_INET6` dual-stack and cannot be created on a kernel with `ipv6.disable=1`; unetd then ran with no peer exchange at all. Open an IPv4 socket on the same port instead. |
| `0011` udht: `-b` bootstrap option, five default routers | The only bootstrap nodes were two hard-coded public routers, so unet-dht could not be tested offline or used in a private DHT. Those two (router.bittorrent.com, router.utorrent.com) also both stopped answering at some point in 2026, from a CI host as much as from a phone, which left every unet-dht unable to bootstrap; the default list now has five entries, and `dht.transmissionbt.com` answered in the same test. |
| `0012` pex: diagnostics for the DHT relay | unet-dht's packets travel through unetd's global PEX socket, and that relay was silent: a failed `sendto()` was invisible, and so was a reply that was or was not forwarded. Failures are reported with the address and errno; the first few relayed packets, the first few received ones (forwarded to the DHT node or not) and the moment the DHT node attaches are logged. Added to find out why a phone's DHT pings got no answer. |

And for wireguard-go (`patches/wireguard-go/`):

| Patch | Why |
|---|---|
| `0001` conn: `AddControlFn` | Lets the embedder apply per-socket configuration before every bind. `Device.BindUpdate()` reopens the sockets on each `listen_port` write, so a `protect()` applied once after start silently stops holding. |
| `0002` ipc: `SetSocketDirectory` | wireguard-android bakes `/data/data/<pkg>/cache/wireguard` in with a linker flag, which is only right for the primary Android user. |

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

## What the host tests prove

| Test | What it exercises |
|---|---|
| M0 `tests/host/m0-uapi-hinge.sh` | unetd, built in the Android configuration, configures wireguard-go entirely over the UAPI socket: private key, peers, AllowedIPs, one `/64` for every derived address. Run twice in CI, the second time with `CAP_NET_RAW` dropped. |
| M1 `tests/dht/nat-testbed.sh` | **DHT discovery with both ends behind NAT**, the topology this app is for. Five network namespaces: a gateway and a phone, each behind its own port-preserving MASQUERADE router with unsolicited WAN input dropped, and an "internet" between them running a private DHT of ten nodes (`tests/dht/dhtnode.c`, on unetd's own `dht.c`). The phone knows only its key and the network's public key. It must find the gateway's external address through the DHT, fetch the signed data over the global PEX socket through both NATs, learn the WireGuard endpoint from PEX, and get a UDP echo back through the tunnel. Passes in about two minutes; the DHT bootstrap is most of it. |
| M1a `tests/host/m1-core.sh` | The library wrapper the app uses (`native/core`), driven the way `UnetVpnService` drives it: start, add network, status JSON with peers, interface-update callback with the `/64` and the IPv4 routes, `protect()` offered for the global PEX socket, remove, stop — **twice in one process**, because the app connects and disconnects without restarting. |
| M1b `tests/host/m1b-dht-relay.sh` | The same wrapper **relays unet-dht**. unet-dht owns no UDP socket: it hands every DHT packet to unetd over the control socket, unetd sends it from the global PEX socket and feeds the reply back through a passed socketpair. The app runs exactly this across its two processes. A ping from unet-dht to a private DHT node on a loopback alias must come back as a pong through the wrapper, and core-test must still finish both of its rounds with the control socket open. |

## The emulator test

`app/src/androidTest/.../TunnelEmulatorTest.kt` drives the app the way a user
does, in the app's own process, so its sockets sit inside the VPN like any
app's. Four ordered tests:

| Test | Proves |
|---|---|
| `t1_baselineInternet` | DNS and HTTP work before any VPN. |
| `t2_splitTunnelKeepsInternet` | With the VPN up on the placeholder tun (no routes yet), the internet still works. Android blocks a whole address family for a VPN that adds nothing of that family, which the service counters with `allowFamily`; this is the regression test. |
| `t3_tunnelCarriesTraffic` | unetd fetched the signed data from the router on the runner, the tunnel re-established with the real addresses, WireGuard handshook, and HTTP to the router's in-tunnel address answers through it. |
| `t3b_stunLearnsExternalPort` | The signed data carries two public STUN servers; unetd queries them and learns an outside port, which the status and the home screen show. On Android it is the peer-exchange port's mapping, since probing the WireGuard port needs a raw socket. |
| `t4_disconnectRemovesVpn` | Disconnecting in the app takes the VPN down and leaves the internet working. |

The VPN consent dialog cannot be clicked on a headless emulator; CI grants the
same app-op the dialog sets: `adb shell appops set org.unetd.android ACTIVATE_VPN allow`.

CI runs the four tests twice (`tests/emulator/run-tests.sh`, then again with
`USE_DHT=1`). In the second pass the saved config has **no gateway**; the app
starts unet-dht in its `:dht` process, bootstrapped from ten private DHT nodes
on the runner (`tests/dht/dhtnode.c`, listening on `10.0.2.2`, the emulator's
name for its host), where the router announces the network with its own
unet-dht. `t3` then proves the whole discovery chain inside the app: DHT lookup,
PEX to the announced address, signed data, WireGuard. The DHT pass waits up to
four minutes for the peer, since bootstrapping alone takes about a minute.
The `-b` bootstrap option (patch 0011) and the "DHT bootstrap nodes" field under
*Advanced* in the setup screen exist for this; empty means the public routers.

On a phone, the Log screen is the equivalent of this job's logcat: it carries
the build, the always-on and lockdown state, every `protect()` result, the
`tun:` line, the netdiag block (routes, DNS, HTTPS, raw TCP, and UDP probes: a
DNS query to 8.8.8.8 and a DHT ping to the three public bootstrap routers, each
from a plain socket and from a `protect()`ed one), the first DHT packets relayed
and received, and the DHT process's own log. The host-tests workflow also pings
the public bootstrap routers with unet-dht from the runner, informationally, so
a phone's missing pong can be compared with a plain host.

One emulator detail cost a run: QEMU's user-mode NAT delivers the guest's
packets to the host from **127.0.0.1**, and a DHT node drops anything from
127.0.0.0/8 as a martian source (`is_martian()` in dht.c), silently. The DHT
nodes therefore listen on `10.0.2.2` only, and `tests/emulator/udp-proxy.py`
on 127.0.0.1 forwards the emulator's packets to them from a second lo alias
(`10.0.2.100`), so the DHT sees a routable peer. (SNAT on loopback does not
work: the packet passes conntrack twice and the reply is never mapped back.)
PEX and WireGuard are left alone, they work from 127.0.0.1.

## Behind carrier-grade NAT

When the gateway sits behind a CGNAT (DS-Lite is the common case), nothing
reaches it unsolicited, firewall rules or not. unetd's design still connects
two such hosts: both announce themselves in the DHT with the port the DHT nodes
saw, both search, and each sends to the other's outside address, which opens
the mappings. That needs three things, and the app's Log screen tells you about
two of them:

- Both DHT nodes alive. The phone's shows up as `Node: <address>` in its DHT
  log; the gateway's needs `unet-dht -d` on the router.
- Endpoint-independent mappings on both NATs. The `nat (plain)` and
  `nat (protected)` lines in the netdiag block classify the phone's NAT by
  asking two STUN servers from one socket. For the CGNAT, configure two
  `stun-servers` in the network and read unetd's `Update external data port`
  lines on the router: equal ports from both servers is the good case.
- Patience: discovery, announcement and the other side's search add up to a
  minute or two.

This was verified on 2 October 2026 with a phone on 5G behind carrier NAT and
an OpenWrt router behind a netfilter NAT with port-restricted filtering:
neither side had a forwarded port, the router's unet-dht (given its network
with `-N`, see "Using it") found the phone's announcement, unetd sent to it,
the phone's requests then passed, and WireGuard came up on the phone's
preserved port 51830 with pings both ways.

A symmetric NAT on either side defeats this, STUN included. The way out is an
IPv6 endpoint for the gateway, which DS-Lite provides natively and which
unetd's dual-stack peer-exchange socket uses as it is, or a relay host with a
public address. The two upstream bootstrap routers also matter here: with both
of them silent, a gateway's DHT dies after a reboot (its node cache lives in
`/var/run`), so the stock OpenWrt `unet-dht` needs patch 0011 or a hosts entry
pointing one of those names at a live router.

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

The native side (patch series, host build, M0, M1a) is verified locally and in
CI, and is reproducible from a clean checkout. M0 also passes in CI with
`CAP_NET_RAW` dropped, which is the case that matters for Android.

The whole tunnel is verified on a **stock Android 17 (API 37) emulator in CI**,
against a unetd router on the same machine (`emulator` job): internet keeps
working with the VPN up and nothing routed, unetd fetches the signed network
data over the global PEX socket, the tunnel re-establishes with the derived
addresses, WireGuard handshakes, HTTP goes through the tunnel to the router,
and disconnecting takes the VPN down again. Two things that run came out of
exactly this test: `allowFamily` (Android blocks an unrouted address family
otherwise) and `ACCESS_LOCAL_NETWORK` (Android 17 refuses packets to LAN
addresses without it, which is where a gateway usually lives).

DHT rendezvous, peer exchange and the WireGuard handshake with **both ends
behind NAT** are verified on Linux (M1, `tests/dht/nat-testbed.sh`), with the
same unetd and wireguard-go code the app embeds, against netfilter NATs that
behave like home routers. One finding from building it is worth knowing:
a Linux NAT that *accepts* unsolicited WAN packets into its own stack (no
`INPUT` drop rule) confirms a conntrack entry for them, and the LAN host's own
mapping then loses its source port; with both sides doing that at once,
nothing ever connects. Routers with a normal WAN firewall do not have this
problem.

The DHT node in the app's own `:dht` process is verified on the emulator too:
the second emulator pass configures no gateway, and the app finds the router
through a private DHT on the runner (see "The emulator test").

Verified on a real network on 2 October 2026: DHT discovery against the
public BitTorrent DHT and the full tunnel between a phone on 5G and an OpenWrt
router, both behind NAT, no forwarded ports (see "Behind carrier-grade NAT").
STUN is verified on the emulator (an outside port learned from a public server).

Not verified anywhere yet: Doze
and roaming on a phone, and GrapheneOS specifics. The app has run on one physical
device so far, where the VPN came up but the device's own VPN settings
(always-on) were still being investigated.
