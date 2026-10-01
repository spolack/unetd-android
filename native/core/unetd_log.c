/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "unetd_log.h"

#define RING_LINES	1024
#define LINE_MAX_LEN	2048

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static char *ring[RING_LINES];
static size_t ring_next;		/* index the next line goes to */
static size_t ring_count;
static bool capturing;
static bool echo;
static int orig_stderr = -1;

static void ring_push(const char *line, size_t len)
{
	char *copy = malloc(len + 1);

	if (!copy)
		return;
	memcpy(copy, line, len);
	copy[len] = 0;

	pthread_mutex_lock(&lock);
	free(ring[ring_next]);
	ring[ring_next] = copy;
	ring_next = (ring_next + 1) % RING_LINES;
	if (ring_count < RING_LINES)
		ring_count++;
	pthread_mutex_unlock(&lock);
}

static void emit(const char *line, size_t len)
{
	ring_push(line, len);
#ifdef __ANDROID__
	{
		char tmp[LINE_MAX_LEN + 1];
		size_t n = len < LINE_MAX_LEN ? len : LINE_MAX_LEN;

		memcpy(tmp, line, n);
		tmp[n] = 0;
		__android_log_write(ANDROID_LOG_INFO, "unetd", tmp);
	}
#endif
	if (echo && orig_stderr >= 0) {
		if (write(orig_stderr, line, len) < 0 || write(orig_stderr, "\n", 1) < 0)
			; /* nothing sensible to do */
	}
}

static void *reader(void *arg)
{
	int fd = (int)(intptr_t)arg;
	static char buf[LINE_MAX_LEN];
	size_t fill = 0;

	for (;;) {
		ssize_t n = read(fd, buf + fill, sizeof(buf) - fill);
		char *start, *nl;

		if (n < 0) {
			if (errno == EINTR)
				continue;
			break;
		}
		if (n == 0)
			break;
		fill += (size_t)n;

		start = buf;
		while ((nl = memchr(start, '\n', fill - (size_t)(start - buf))) != NULL) {
			emit(start, (size_t)(nl - start));
			start = nl + 1;
		}
		fill -= (size_t)(start - buf);
		memmove(buf, start, fill);
		if (fill == sizeof(buf)) {
			/* an over-long line: flush it as is */
			emit(buf, fill);
			fill = 0;
		}
	}
	return NULL;
}

int unetd_log_capture_start(bool echo_to_original)
{
	pthread_attr_t attr;
	pthread_t thread;
	int fds[2];

	pthread_mutex_lock(&lock);
	if (capturing) {
		pthread_mutex_unlock(&lock);
		return 0;
	}
	capturing = true;
	echo = echo_to_original;
	pthread_mutex_unlock(&lock);

	if (pipe(fds) < 0)
		return -1;
	fcntl(fds[0], F_SETFD, FD_CLOEXEC);
	fcntl(fds[1], F_SETFD, FD_CLOEXEC);

	orig_stderr = dup(STDERR_FILENO);
	fcntl(orig_stderr, F_SETFD, FD_CLOEXEC);

	/* Line discipline: whoever writes gets it to the reader immediately. */
	setvbuf(stdout, NULL, _IOLBF, 0);
	setvbuf(stderr, NULL, _IONBF, 0);
	dup2(fds[1], STDOUT_FILENO);
	dup2(fds[1], STDERR_FILENO);
	close(fds[1]);

	pthread_attr_init(&attr);
	pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
	if (pthread_create(&thread, &attr, reader, (void *)(intptr_t)fds[0]) != 0) {
		pthread_attr_destroy(&attr);
		return -1;
	}
	pthread_attr_destroy(&attr);
	return 0;
}

char *unetd_log_tail(size_t max_lines)
{
	size_t total = 1, i, n, first;
	char *out, *p;

	pthread_mutex_lock(&lock);
	n = ring_count < max_lines ? ring_count : max_lines;
	first = (ring_next + RING_LINES - n) % RING_LINES;
	for (i = 0; i < n; i++)
		total += strlen(ring[(first + i) % RING_LINES]) + 1;
	out = malloc(total);
	if (out) {
		p = out;
		for (i = 0; i < n; i++) {
			const char *l = ring[(first + i) % RING_LINES];
			size_t len = strlen(l);

			memcpy(p, l, len);
			p += len;
			*p++ = '\n';
		}
		*p = 0;
	}
	pthread_mutex_unlock(&lock);
	return out;
}
