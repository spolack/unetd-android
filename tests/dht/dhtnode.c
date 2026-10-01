/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * A bare BitTorrent-DHT node on top of unetd's dht.c (jech/dht), so a private
 * DHT of a dozen nodes can stand in for the public one in the NAT testbed.
 * It stores and answers announcements like any other node and does nothing
 * else.
 *
 * Usage: dhtnode <bind-ip> <port> [bootstrap-ip:port ...]
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "dht.h"

/* ---- callbacks dht.c expects from its host ---------------------------------- */

int dht_sendto(int sockfd, const void *buf, int len, int flags,
	       const struct sockaddr *to, int tolen)
{
	return sendto(sockfd, buf, len, flags, to, tolen);
}

int dht_blacklisted(const struct sockaddr *sa, int salen)
{
	return 0;
}

/* Only used for the tokens this node hands out; any mixing function will do. */
void dht_hash(void *hash_return, int hash_size, const void *v1, int len1,
	      const void *v2, int len2, const void *v3, int len3)
{
	uint64_t h = 1469598103934665603ULL;
	const unsigned char *p;
	int i;

#define MIX(ptr, n) for (p = (ptr), i = 0; i < (n); i++) h = (h ^ p[i]) * 1099511628211ULL;
	MIX(v1, len1);
	MIX(v2, len2);
	MIX(v3, len3);
#undef MIX
	for (i = 0; i < hash_size; i++) {
		((unsigned char *)hash_return)[i] = (unsigned char)(h >> ((i % 8) * 8));
		if (i % 8 == 7)
			h = h * 6364136223846793005ULL + 1442695040888963407ULL;
	}
}

int dht_random_bytes(void *buf, size_t size)
{
	int fd = open("/dev/urandom", O_RDONLY);
	int n;

	if (fd < 0)
		return -1;
	n = read(fd, buf, size);
	close(fd);
	return n;
}

static void event_cb(void *closure, int event, const unsigned char *info_hash,
		     const void *data, size_t data_len)
{
}

int main(int argc, char **argv)
{
	unsigned char id[20];
	struct sockaddr_in sin = { .sin_family = AF_INET };
	time_t tosleep = 0, last_report = 0;
	int s, i;

	if (argc < 3) {
		fprintf(stderr, "usage: %s <bind-ip> <port> [bootstrap-ip:port ...]\n", argv[0]);
		return 2;
	}

	s = socket(AF_INET, SOCK_DGRAM, 0);
	sin.sin_port = htons(atoi(argv[2]));
	if (inet_pton(AF_INET, argv[1], &sin.sin_addr) != 1 ||
	    bind(s, (struct sockaddr *)&sin, sizeof(sin)) < 0) {
		perror("bind");
		return 1;
	}

	dht_random_bytes(id, sizeof(id));
	if (dht_init(s, -1, id, (const unsigned char *)"UN\0\0") < 0) {
		perror("dht_init");
		return 1;
	}

	for (i = 3; i < argc; i++) {
		struct sockaddr_in peer = { .sin_family = AF_INET };
		char *spec = strdup(argv[i]), *port = strrchr(spec, ':');

		if (port)
			*(port++) = 0;
		peer.sin_port = htons(port ? atoi(port) : 6881);
		if (inet_pton(AF_INET, spec, &peer.sin_addr) == 1)
			dht_ping_node((struct sockaddr *)&peer, sizeof(peer));
		free(spec);
	}

	for (;;) {
		struct pollfd pfd = { .fd = s, .events = POLLIN };
		unsigned char buf[4096];
		struct sockaddr_in from;
		socklen_t fromlen = sizeof(from);
		int rc = poll(&pfd, 1, (int)(tosleep > 0 ? tosleep * 1000 : 1000));
		int good = 0, dubious = 0, incoming = 0;
		time_t now = time(NULL);

		if (rc > 0) {
			int len = recvfrom(s, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&from, &fromlen);

			if (len < 0) {
				if (errno == EINTR || errno == EAGAIN)
					continue;
				perror("recvfrom");
				return 1;
			}
			buf[len] = 0;
			dht_periodic(buf, len, (struct sockaddr *)&from, fromlen, &tosleep, event_cb, NULL);
		} else {
			dht_periodic(NULL, 0, NULL, 0, &tosleep, event_cb, NULL);
		}

		if (now - last_report >= 10) {
			last_report = now;
			dht_nodes(AF_INET, &good, &dubious, NULL, &incoming);
			fprintf(stderr, "%s:%s nodes good=%d dubious=%d incoming=%d\n",
				argv[1], argv[2], good, dubious, incoming);
		}
	}
}
