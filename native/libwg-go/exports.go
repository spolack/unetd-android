// SPDX-License-Identifier: GPL-3.0-or-later
//go:build android && cgo

package main

import "C"

// The C-visible entry points, one line each, so api.go stays free of cgo and
// can be type-checked for GOOS=android without an NDK (CGO_ENABLED=0).

//export wgTurnOn
func wgTurnOn(socketDir, uapiName string, tunFd int32) int32 {
	return turnOn(socketDir, uapiName, tunFd)
}

//export wgTurnOff
func wgTurnOff(handle int32) { turnOff(handle) }

//export wgVersion
func wgVersion() *C.char { return C.CString(version()) }
