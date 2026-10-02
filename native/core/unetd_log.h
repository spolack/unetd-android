/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UNETD_LOG_H
#define UNETD_LOG_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * The native layer's log: a ring of recent lines, each with a sequence number,
 * so a reader can fetch only what it has not seen yet.
 *
 * Lines get in two ways. unetd_log_push() is the direct path for the parts of
 * the native layer that are ours (the core wrapper, the JNI glue, lines from
 * Kotlin). The capture of fd 2 is the path for everything upstream writes with
 * fprintf(stderr)/perror -- unetd is full of those and wireguard-go's logger
 * writes there too -- and, on Android only, of fd 1, which would otherwise be
 * lost (unet-dht prints its progress with printf). On the host stdout is left
 * alone: the test programs print their results there.
 *
 * Every line is also forwarded to logcat on Android. Thread-safe throughout.
 */

/* One or more lines ('\n'-separated; a trailing newline is not a line). */
void unetd_log_push(const char *text);

/* Idempotent. echo_to_original keeps writing captured lines to the original
 * stderr as well, which is what the host tests want. */
int unetd_log_capture_start(bool echo_to_original);

/*
 * The lines with sequence numbers >= since, oldest first, at most max_lines of
 * them, newline-terminated, malloc'd; the caller frees. *next receives the
 * sequence number to ask for next time (lines still missing after max_lines
 * are returned on the following call). since = 0 means everything the ring
 * still holds. NULL on allocation failure.
 */
char *unetd_log_since(uint64_t since, size_t max_lines, uint64_t *next);

/* The sequence number the next line will get. */
uint64_t unetd_log_seq(void);

/* The most recent lines, newline-separated, malloc'd; the caller frees. */
char *unetd_log_tail(size_t max_lines);

#endif
