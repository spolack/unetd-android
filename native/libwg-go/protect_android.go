// SPDX-License-Identifier: GPL-3.0-or-later
//go:build android && cgo

package main

/*
#include <stdbool.h>
extern bool wg_protect_fd(int fd);
*/
import "C"

// protectFd is VpnService.protect(fd) through jni.c, on whatever thread the
// bind happens to run on.
var protectFd = func(fd int) bool {
	return bool(C.wg_protect_fd(C.int(fd)))
}
