// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"errors"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

// fakeBind records the calls protectedBind makes and pretends to own two
// sockets, like StdNetBind on Android.
type fakeBind struct {
	conn.Bind
	opened, closed int
	fd4, fd6       int
}

func (f *fakeBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	f.opened++
	return nil, port, nil
}
func (f *fakeBind) Close() error                      { f.closed++; return nil }
func (f *fakeBind) PeekLookAtSocketFd4() (int, error) { return f.fd4, nil }
func (f *fakeBind) PeekLookAtSocketFd6() (int, error) {
	if f.fd6 < 0 {
		return -1, errors.New("no IPv6 socket")
	}
	return f.fd6, nil
}

func TestOpenProtectsEverySocket(t *testing.T) {
	inner := &fakeBind{fd4: 40, fd6: 60}
	var seen []int
	b := newProtectedBind(inner, func(fd int) bool { seen = append(seen, fd); return true })
	if _, _, err := b.Open(0); err != nil {
		t.Fatal(err)
	}
	if inner.opened != 1 || inner.closed != 0 {
		t.Fatalf("inner opened=%d closed=%d", inner.opened, inner.closed)
	}
	if len(seen) != 2 || seen[0] != 40 || seen[1] != 60 {
		t.Fatalf("protect saw %v, want [40 60]", seen)
	}
}

func TestOpenSkipsAnUnopenedFamily(t *testing.T) {
	inner := &fakeBind{fd4: 40, fd6: -1}
	var seen []int
	b := newProtectedBind(inner, func(fd int) bool { seen = append(seen, fd); return true })
	if _, _, err := b.Open(0); err != nil {
		t.Fatal(err)
	}
	if len(seen) != 1 || seen[0] != 40 {
		t.Fatalf("protect saw %v, want [40]", seen)
	}
}

func TestRefusedProtectClosesAndFails(t *testing.T) {
	inner := &fakeBind{fd4: 40, fd6: 60}
	b := newProtectedBind(inner, func(fd int) bool { return fd != 60 })
	if _, _, err := b.Open(0); err == nil {
		t.Fatal("Open succeeded although protect refused a socket")
	}
	if inner.closed != 1 {
		t.Fatalf("inner closed %d times, want 1", inner.closed)
	}
}

func TestNoProtectorMeansNothingToDo(t *testing.T) {
	inner := &fakeBind{fd4: 40, fd6: 60}
	if _, _, err := newProtectedBind(inner, nil).Open(0); err != nil {
		t.Fatal(err)
	}
}

// The real StdNetBind on the host does not implement PeekLookAtSocketFd; the
// wrapper must then open and close like the plain bind.
func TestWrappedStdNetBindOpensOnHost(t *testing.T) {
	b := newProtectedBind(conn.NewStdNetBind(), func(int) bool { t.Fatal("protect called on host"); return false })
	fns, port, err := b.Open(0)
	if err != nil {
		t.Skipf("cannot open UDP sockets here: %v", err)
	}
	// Upstream reports port 0 when the IPv6 listen fails (a kernel with
	// ipv6.disable=1), so only the receive functions are checked.
	if len(fns) == 0 {
		t.Fatalf("no receive functions (port %d)", port)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
}
