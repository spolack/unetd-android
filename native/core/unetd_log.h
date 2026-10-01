/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UNETD_LOG_H
#define UNETD_LOG_H

#include <stdbool.h>
#include <stddef.h>

/*
 * Capture everything the native layer writes to stdout and stderr -- unetd's
 * fprintf(stderr)/perror diagnostics, its debug output, wireguard-go's logger
 * -- into a ring of recent lines that the app can show, and forward each line
 * to logcat on Android. Idempotent; safe to call from any thread.
 *
 * echo_to_original keeps writing the lines to the original stderr as well,
 * which is what the host tests want.
 */
int unetd_log_capture_start(bool echo_to_original);

/* The most recent lines, newline-separated, malloc'd; the caller frees. */
char *unetd_log_tail(size_t max_lines);

#endif
