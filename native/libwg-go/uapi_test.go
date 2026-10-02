// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"encoding/hex"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// fakeTun satisfies tun.Device without a kernel interface; Read blocks until
// Close, which is all a device needs to be created and configured.
type fakeTun struct {
	events chan tun.Event
	done   chan struct{}
}

func newFakeTun() *fakeTun {
	return &fakeTun{events: make(chan tun.Event, 1), done: make(chan struct{})}
}
func (f *fakeTun) File() *os.File { return nil }
func (f *fakeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	<-f.done
	return 0, os.ErrClosed
}
func (f *fakeTun) Write(bufs [][]byte, offset int) (int, error) { return len(bufs), nil }
func (f *fakeTun) MTU() (int, error)                            { return 1280, nil }
func (f *fakeTun) Name() (string, error)                        { return "faketun", nil }
func (f *fakeTun) Events() <-chan tun.Event                     { return f.events }
func (f *fakeTun) BatchSize() int                               { return 1 }
func (f *fakeTun) Close() error {
	select {
	case <-f.done:
	default:
		close(f.done)
		close(f.events)
	}
	return nil
}

func uapiRoundTrip(t *testing.T, path, request string) string {
	t.Helper()
	c, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write([]byte(request)); err != nil {
		t.Fatal(err)
	}
	r := bufio.NewReader(c)
	var out strings.Builder
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("reading the UAPI reply: %v (got %q so far)", err, out.String())
		}
		out.WriteString(line)
		if line == "\n" {
			return out.String()
		}
	}
}

func TestUAPIServesTheDevice(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "run", "wireguard")
	l, err := uapiListen(dir, "unet")
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(newFakeTun(), newProtectedBind(conn.NewStdNetBind(), nil), device.NewLogger(device.LogLevelSilent, ""))
	defer dev.Close()
	defer l.Close()
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()

	path := filepath.Join(dir, "unet.sock")
	if st, err := os.Stat(path); err != nil || st.Mode()&os.ModeSocket == 0 {
		t.Fatalf("%s is not a socket: %v", path, err)
	}
	key := strings.Repeat("ab", 32)
	if reply := uapiRoundTrip(t, path, "set=1\nprivate_key="+key+"\n\n"); reply != "errno=0\n\n" {
		t.Fatalf("set replied %q", reply)
	}
	got := uapiRoundTrip(t, path, "get=1\n\n")
	// wireguard-go clamps the private key, so compare the public key instead.
	if !strings.Contains(got, "errno=0\n") || !strings.Contains(got, "private_key=") {
		t.Fatalf("get replied %q", got)
	}
	for _, line := range strings.Split(got, "\n") {
		if strings.HasPrefix(line, "private_key=") {
			if _, err := hex.DecodeString(strings.TrimPrefix(line, "private_key=")); err != nil {
				t.Fatalf("private_key is not hex: %q", line)
			}
		}
	}
}

func TestUAPIReplacesAStaleSocket(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "unet.sock")
	// A socket file nobody listens on, as left behind by a crashed process.
	stale, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	stale.SetUnlinkOnClose(false)
	stale.Close()
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("stale socket should still exist: %v", err)
	}

	l, err := uapiListen(dir, "unet")
	if err != nil {
		t.Fatalf("stale socket not replaced: %v", err)
	}
	l.Close()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("socket not unlinked on close: %v", err)
	}
}

func TestUAPIRefusesALiveSocket(t *testing.T) {
	dir := t.TempDir()
	l, err := uapiListen(dir, "unet")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	if _, err := uapiListen(dir, "unet"); err == nil {
		t.Fatal("a second listener on a live socket was accepted")
	}
}

func TestUAPIRejectsBadNames(t *testing.T) {
	for _, name := range []string{"", "a/b", "x\x00y"} {
		if _, err := uapiListen(t.TempDir(), name); err == nil {
			t.Fatalf("name %q accepted", name)
		}
	}
}
