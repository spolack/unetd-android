// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
)

// uapiListen serves the WireGuard UAPI on <dir>/<name>.sock.
//
// wireguard-go's ipc.UAPIOpen bakes its socket directory in at link time (the
// WireGuard app uses /data/data/<pkg>/cache, which is wrong for secondary
// users and work profiles), and ipc.UAPIListen watches that same baked-in path
// with inotify. Both are a few lines, so they are done here with the directory
// the app actually has. The directory is private to the app (0700), so no
// umask dance is needed. unetd's wg-user.c connects to exactly this path.
func uapiListen(dir, name string) (*net.UnixListener, error) {
	if name == "" || strings.ContainsAny(name, "/\x00") {
		return nil, fmt.Errorf("invalid UAPI socket name %q", name)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, name+".sock")
	addr := &net.UnixAddr{Name: path, Net: "unix"}

	l, err := net.ListenUnix("unix", addr)
	if err != nil {
		// A socket file left behind by a crashed process, or one still in use.
		if c, derr := net.Dial("unix", path); derr == nil {
			c.Close()
			return nil, fmt.Errorf("UAPI socket %s is in use", path)
		}
		if rerr := os.Remove(path); rerr != nil && !os.IsNotExist(rerr) {
			return nil, rerr
		}
		l, err = net.ListenUnix("unix", addr)
		if err != nil {
			return nil, err
		}
	}
	l.SetUnlinkOnClose(true)
	return l, nil
}
