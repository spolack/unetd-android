// SPDX-License-Identifier: GPL-3.0-or-later
//
// Derived from wireguard-android's tunnel/tools/libwg-go/api-android.go,
// Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>, Apache-2.0.
//
// Differences from upstream, each deliberate:
//
//   - The UAPI socket is named after the unet network, not after the kernel
//     tun name, and lives in a directory passed at call time (uapi.go). unetd's
//     wg-user.c looks for <dir>/<network>.sock, and the kernel name of a
//     VpnService tun (tun0, tun1, ...) is not under our control.
//   - Every UDP socket is protect()ed when it is opened, through a conn.Bind
//     wrapper (bind.go), so it stays protected across the rebinds unetd
//     triggers by writing listen_port.
//   - A refused protect() is fatal for that bind: wireguard-go then reports
//     the listen_port write as failed instead of sending into its own tunnel.
//   - Each log line is one write(2) to stderr, so the native log capture sees
//     whole lines even when unetd writes at the same time.
//
// Strings arriving through cgo point at the caller's C buffer, which jni.c
// releases as soon as the call returns. Anything kept beyond the call must be
// copied first (strings.Clone), or it dangles.

//go:build android

package main

import (
	"fmt"
	"math"
	"net"
	"os"
	"runtime/debug"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type stderrLogger struct {
	mu     sync.Mutex
	prefix string
}

func (l *stderrLogger) printf(format string, args ...interface{}) {
	line := l.prefix + fmt.Sprintf(format, args...) + "\n"
	l.mu.Lock()
	os.Stderr.WriteString(line)
	l.mu.Unlock()
}

type tunnelHandle struct {
	device *device.Device
	uapi   net.Listener
}

var (
	handlesMu     sync.Mutex
	tunnelHandles = map[int32]tunnelHandle{}
)

func turnOn(socketDir, uapiName string, tunFd int32) int32 {
	socketDir = strings.Clone(socketDir)
	uapiName = strings.Clone(uapiName)
	log := &stderrLogger{prefix: "wg[" + uapiName + "] "}
	logger := &device.Logger{
		Verbosef: log.printf,
		Errorf:   func(format string, args ...interface{}) { log.printf("error: "+format, args...) },
	}

	tunDev, kernelName, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}
	logger.Verbosef("attached to %s, serving UAPI as %s/%s.sock", kernelName, socketDir, uapiName)

	dev := device.NewDevice(tunDev, newProtectedBind(conn.NewStdNetBind(), protectFd), logger)
	// Roaming stays on, as in the kernel: a peer's endpoint follows the source
	// of its authenticated packets. unetd was written against that: it polls
	// the endpoint back (wg-user.c, "endpoint") to notice a peer whose NAT
	// mapping differs from what STUN or peer exchange announced, and tells the
	// other peers. The WireGuard app's DisableSomeRoamingForBrokenMobileSemantics
	// would pin every UAPI-set endpoint, so a handshake initiation arriving
	// from a different port than the announced one would be answered to the
	// announced port and die in the peer's NAT. unetd rewrites the endpoint
	// once a second while a peer is down anyway, which bounds the damage a
	// wrong roam could do.

	// unetd needs this socket; without it there is nothing to drive, so unlike
	// the WireGuard app a failure here is fatal rather than logged and ignored.
	uapi, err := uapiListen(socketDir, uapiName)
	if err != nil {
		logger.Errorf("UAPI: %v", err)
		dev.Close()
		return -1
	}
	go func() {
		for {
			c, err := uapi.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()

	if err := dev.Up(); err != nil {
		logger.Errorf("Up: %v", err)
		uapi.Close()
		dev.Close()
		return -1
	}
	logger.Verbosef("device started")

	handlesMu.Lock()
	defer handlesMu.Unlock()
	var i int32
	for i = 0; i < math.MaxInt32; i++ {
		if _, exists := tunnelHandles[i]; !exists {
			break
		}
	}
	if i == math.MaxInt32 {
		logger.Errorf("no free handle")
		uapi.Close()
		dev.Close()
		return -1
	}
	tunnelHandles[i] = tunnelHandle{device: dev, uapi: uapi}
	return i
}

func turnOff(handle int32) {
	handlesMu.Lock()
	h, ok := tunnelHandles[handle]
	if ok {
		delete(tunnelHandles, handle)
	}
	handlesMu.Unlock()
	if !ok {
		return
	}
	h.uapi.Close()
	h.device.Close() // also closes the tun fd it owns
}

func version() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	for _, dep := range info.Deps {
		if dep.Path != "golang.zx2c4.com/wireguard" {
			continue
		}
		v := dep.Version
		if dep.Replace != nil {
			v = dep.Replace.Version
		}
		// A pseudo-version (v0.0.0-<date>-<12 hex>) names the upstream commit.
		if parts := strings.Split(v, "-"); len(parts) == 3 && len(parts[2]) == 12 {
			return parts[2][:7]
		}
		return v
	}
	return "unknown"
}
