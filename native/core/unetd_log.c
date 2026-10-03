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

#define RING_LINES	8192
#define LINE_MAX_LEN	2048

struct line {
	uint64_t seq;
	char *text;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static struct line ring[RING_LINES];
static uint64_t next_seq = 1;		/* the sequence number of the next line */
static size_t ring_count;
static bool capturing;
static bool echo;
static int orig_stderr = -1;

static void ring_push(const char *text, size_t len)
{
	char *copy = malloc(len + 1);
	struct line *l;

	if (!copy)
		return;
	memcpy(copy, text, len);
	copy[len] = 0;

	pthread_mutex_lock(&lock);
	l = &ring[(next_seq - 1) % RING_LINES];
	free(l->text);
	l->text = copy;
	l->seq = next_seq++;
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

void unetd_log_push(const char *text)
{
	const char *nl;

	if (!text)
		return;
	while ((nl = strchr(text, '\n')) != NULL) {
		emit(text, (size_t)(nl - text));
		text = nl + 1;
	}
	if (*text)
		emit(text, strlen(text));
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
	if (pipe(fds) < 0) {
		pthread_mutex_unlock(&lock);
		return -1;
	}
	capturing = true;
	echo = echo_to_original;
	pthread_mutex_unlock(&lock);

	fcntl(fds[0], F_SETFD, FD_CLOEXEC);
	fcntl(fds[1], F_SETFD, FD_CLOEXEC);

	orig_stderr = dup(STDERR_FILENO);
	fcntl(orig_stderr, F_SETFD, FD_CLOEXEC);

	/* Line discipline: whoever writes gets it to the reader immediately. */
	setvbuf(stderr, NULL, _IONBF, 0);
	dup2(fds[1], STDERR_FILENO);
#ifdef __ANDROID__
	setvbuf(stdout, NULL, _IOLBF, 0);
	dup2(fds[1], STDOUT_FILENO);
#endif
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

/* Joins the lines with sequence numbers in [from, to). Called under the lock. */
static char *join_locked(uint64_t from, uint64_t to)
{
	size_t total = 1;
	uint64_t s;
	char *out, *p;

	for (s = from; s < to; s++)
		total += strlen(ring[(s - 1) % RING_LINES].text) + 1;
	out = malloc(total);
	if (!out)
		return NULL;
	p = out;
	for (s = from; s < to; s++) {
		const char *l = ring[(s - 1) % RING_LINES].text;
		size_t len = strlen(l);

		memcpy(p, l, len);
		p += len;
		*p++ = '\n';
	}
	*p = 0;
	return out;
}

char *unetd_log_since(uint64_t since, size_t max_lines, uint64_t *next)
{
	uint64_t oldest, from, to;
	char *out;

	pthread_mutex_lock(&lock);
	oldest = next_seq - ring_count;
	from = since > oldest ? since : oldest;
	if (from > next_seq)
		from = next_seq;
	to = next_seq;
	if (to - from > max_lines)
		to = from + max_lines;
	out = join_locked(from, to);
	if (next)
		*next = to;
	pthread_mutex_unlock(&lock);
	return out;
}

uint64_t unetd_log_seq(void)
{
	uint64_t seq;

	pthread_mutex_lock(&lock);
	seq = next_seq;
	pthread_mutex_unlock(&lock);
	return seq;
}

char *unetd_log_tail(size_t max_lines)
{
	size_t n;
	char *out;

	pthread_mutex_lock(&lock);
	n = ring_count < max_lines ? ring_count : max_lines;
	out = join_locked(next_seq - n, next_seq);
	pthread_mutex_unlock(&lock);
	return out;
}
