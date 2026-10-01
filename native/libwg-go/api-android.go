// SPDX-License-Identifier: GPL-3.0-or-later
//
// Derived from wireguard-android's tunnel/tools/libwg-go/api-android.go,
// Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>, Apache-2.0.
//
// Differences from upstream, each deliberate:
//
//   - The UAPI socket is named after the unet network, not after the kernel
//     tun name. wg-user.c in unetd looks for <dir>/<network>.sock and the
//     kernel name of a VpnService tun (tun0, tun1, ...) is not under our
//     control.
//   - The socket directory is set at runtime (wgSetSocketDirectory) instead
//     of being baked in with a linker flag, so it is right for secondary
//     Android users and work profiles too.
//   - Every UDP socket is protect()ed at bind time through a control function.
//     wireguard-go rebinds on every listen_port write, and unetd writes that
//     field whenever the local host changes and twice per STUN cycle, so a
//     one-time protect() after wgTurnOn() would silently stop applying.

package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
extern void wg_protect_fd(int fd);
*/
import "C"

import (
	"fmt"
	"math"
	"net"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/tun"
)

// stderrLogger writes to the process's stderr, which the unetd side of the app
// captures into its log ring and forwards to logcat. One sink for both halves
// of the native layer keeps the in-app log in order.
type stderrLogger struct {
	prefix string
}

func (l stderrLogger) Printf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, l.prefix+format+"\n", args...)
}

type tunnelHandle struct {
	device *device.Device
	uapi   net.Listener
}

var (
	handlesMu     sync.Mutex
	tunnelHandles = map[int32]tunnelHandle{}
)

func init() {
	// Applied before bind to every socket the device opens, on every rebind.
	conn.AddControlFn(func(network, address string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) {
			C.wg_protect_fd(C.int(fd))
		})
	})
}

//export wgSetSocketDirectory
func wgSetSocketDirectory(dir string) {
	ipc.SetSocketDirectory(dir)
}

//export wgTurnOn
func wgTurnOn(uapiName string, tunFd int32, settings string) int32 {
	logger := &device.Logger{
		Verbosef: stderrLogger{prefix: "wg[" + uapiName + "] "}.Printf,
		Errorf:   stderrLogger{prefix: "wg[" + uapiName + "] error: "}.Printf,
	}

	tunDev, kernelName, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}
	logger.Verbosef("attached to %s, serving UAPI as %s", kernelName, uapiName)

	dev := device.NewDevice(tunDev, conn.NewStdNetBind(), logger)

	if err := dev.IpcSet(settings); err != nil {
		logger.Errorf("IpcSet: %v", err)
		dev.Close()
		return -1
	}
	dev.DisableSomeRoamingForBrokenMobileSemantics()

	// unetd needs this socket; without it there is nothing to drive, so unlike
	// upstream a failure here is fatal rather than logged and ignored.
	uapiFile, err := ipc.UAPIOpen(uapiName)
	if err != nil {
		logger.Errorf("UAPIOpen: %v", err)
		dev.Close()
		return -1
	}
	uapi, err := ipc.UAPIListen(uapiName, uapiFile)
	if err != nil {
		logger.Errorf("UAPIListen: %v", err)
		uapiFile.Close()
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

//export wgTurnOff
func wgTurnOff(handle int32) {
	handlesMu.Lock()
	h, ok := tunnelHandles[handle]
	if ok {
		delete(tunnelHandles, handle)
	}
	handlesMu.Unlock()
	if !ok {
		return
	}
	if h.uapi != nil {
		h.uapi.Close()
	}
	h.device.Close()
}

func lookup(handle int32) (tunnelHandle, bool) {
	handlesMu.Lock()
	defer handlesMu.Unlock()
	h, ok := tunnelHandles[handle]
	return h, ok
}

//export wgGetSocketV4
func wgGetSocketV4(handle int32) int32 {
	h, ok := lookup(handle)
	if !ok {
		return -1
	}
	bind, _ := h.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetSocketV6
func wgGetSocketV6(handle int32) int32 {
	h, ok := lookup(handle)
	if !ok {
		return -1
	}
	bind, _ := h.device.Bind().(conn.PeekLookAtSocketFd)
	if bind == nil {
		return -1
	}
	fd, err := bind.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}

//export wgGetConfig
func wgGetConfig(handle int32) *C.char {
	h, ok := lookup(handle)
	if !ok {
		return nil
	}
	settings, err := h.device.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(settings)
}

//export wgVersion
func wgVersion() *C.char {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return C.CString("unknown")
	}
	for _, dep := range info.Deps {
		if dep.Path == "golang.zx2c4.com/wireguard" {
			v := dep.Version
			if dep.Replace != nil {
				v = dep.Replace.Version
			}
			parts := strings.Split(v, "-")
			if len(parts) == 3 && len(parts[2]) == 12 {
				return C.CString(parts[2][:7])
			}
			return C.CString(v)
		}
	}
	return C.CString("unknown")
}

func main() {}
