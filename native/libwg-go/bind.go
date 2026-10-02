// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"fmt"

	"golang.zx2c4.com/wireguard/conn"
)

// protectedBind wraps the device's conn.Bind so that every UDP socket it opens
// is handed to VpnService.protect() before the device sends or receives on it.
//
// wireguard-go closes and reopens its sockets on every listen_port write
// (Device.BindUpdate), and unetd writes listen_port whenever the local host
// changes and twice per STUN cycle. A protect() applied once after start-up,
// the way the WireGuard app does it, therefore silently stops holding. Doing
// it inside Open() makes it hold across every rebind without patching
// wireguard-go: conn.Bind is a public interface.
//
// Endpoints are passed through untouched: StdNetBind.Send type-asserts its own
// endpoint type, so this wrapper must never rewrap what the inner bind returns.
type protectedBind struct {
	conn.Bind
	// protect returns false when the socket must not be used. nil means the
	// platform has nothing to protect (the host build).
	protect func(fd int) bool
}

func newProtectedBind(inner conn.Bind, protect func(fd int) bool) conn.Bind {
	return &protectedBind{Bind: inner, protect: protect}
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actual, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	if err := b.protectAll(); err != nil {
		b.Bind.Close()
		return nil, 0, err
	}
	return fns, actual, nil
}

// protectAll offers every open socket of the inner bind to protect(). The fds
// come from PeekLookAtSocketFd, which StdNetBind implements on Android only;
// elsewhere there is nothing to do.
func (b *protectedBind) protectAll() error {
	if b.protect == nil {
		return nil
	}
	peek, ok := b.Bind.(conn.PeekLookAtSocketFd)
	if !ok {
		return nil
	}
	for _, get := range []func() (int, error){peek.PeekLookAtSocketFd4, peek.PeekLookAtSocketFd6} {
		fd, err := get()
		if err != nil || fd < 0 {
			continue // that address family is not open
		}
		if !b.protect(fd) {
			return fmt.Errorf("VpnService.protect(%d) refused; the socket would send into the tunnel", fd)
		}
	}
	return nil
}
