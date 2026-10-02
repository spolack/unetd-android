// SPDX-License-Identifier: GPL-3.0-or-later
//go:build !android || !cgo

package main

// Only Android (with cgo, i.e. the real build) has a VpnService to protect
// sockets from; elsewhere nil tells protectedBind to skip the step.
var protectFd func(fd int) bool
