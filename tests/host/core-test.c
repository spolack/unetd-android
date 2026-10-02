/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Exercises the unetd library wrapper (native/core) the way the Android
 * service will, with no JNI and no device:
 *
 *   start -> network_add -> status has the peers -> update callback carried the
 *   addresses -> network_remove -> stop -> start again in the same process.
 *
 * The last step is the one that matters most: the app connects and disconnects
 * repeatedly within one process, so uloop_init()/uloop_done() and unetd's
 * file-scope state have to survive a full cycle.
 *
 * Usage: core-test <data-dir> <socket-dir> <network-json>
 * Environment: CORE_TEST_UNIX_SOCKET (control socket path), CORE_TEST_HOLD (seconds)
 * A wireguard-go instance must already be serving <socket-dir>/<name>.sock.
 */
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "unetd_core.h"
#include "unetd_log.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static char *last_update;
static int updates_up, updates_down, protects;

static void on_update(void *priv, const char *json)
{
	pthread_mutex_lock(&lock);
	free(last_update);
	last_update = strdup(json);
	if (strstr(json, "\"link-up\":true"))
		updates_up++;
	else
		updates_down++;
	pthread_mutex_unlock(&lock);
}

static void on_protect(void *priv, int fd)
{
	pthread_mutex_lock(&lock);
	protects++;
	pthread_mutex_unlock(&lock);
}

static void fail(const char *what)
{
	fprintf(stderr, "FAIL: %s\n", what);
	usleep(300 * 1000);	/* let the capture thread echo the line */
	exit(1);
}

/* The delta API: a reader that remembers its sequence number sees each line once. */
static void check_log_since(void)
{
	uint64_t next = 0, next2 = 0;
	char *delta;

	free(unetd_log_since(0, 10000, &next));	/* catch up */
	if (next != unetd_log_seq())
		fail("log_since did not catch up to log_seq");
	unetd_log_push("core-test: delta one\ncore-test: delta two");
	delta = unetd_log_since(next, 10000, &next2);
	if (!delta || strcmp(delta, "core-test: delta one\ncore-test: delta two\n") != 0)
		fail("log_since did not return exactly the two pushed lines");
	free(delta);
	if (next2 != next + 2)
		fail("log_since advanced the sequence number wrongly");
	delta = unetd_log_since(next, 1, &next2);
	if (!delta || strcmp(delta, "core-test: delta one\n") != 0 || next2 != next + 1)
		fail("log_since did not honour max_lines");
	free(delta);
	delta = unetd_log_since(next + 2, 10000, &next2);
	if (!delta || *delta || next2 != next + 2)
		fail("log_since returned lines past the end");
	free(delta);
}

static char *wait_status(const char *needle, int tries)
{
	char *status = NULL;

	while (tries-- > 0) {
		free(status);
		status = unetd_core_status_json();
		if (status && strstr(status, needle))
			return status;
		usleep(200 * 1000);
	}
	fprintf(stderr, "last status: %s\n", status ? status : "(null)");
	free(status);
	return NULL;
}

static void one_cycle(const struct unetd_core_config *cfg,
		      const struct unetd_core_callbacks *cb, const char *json, int round)
{
	char *status, *key;
	int up_before, protects_before;
	bool pex_socket;

	pthread_mutex_lock(&lock);
	up_before = updates_up;
	protects_before = protects;
	pthread_mutex_unlock(&lock);

	if (unetd_core_start(cfg, cb))
		fail("start");
	if (!unetd_core_running())
		fail("not running after start");
	if (unetd_core_start(cfg, cb) == 0)
		fail("second start should be refused");

	if (unetd_core_network_add(json))
		fail("network_add");
	if (unetd_core_network_add("{not json"))
		;
	else
		fail("invalid json accepted");

	/* unetd's wg-user backend only learns about peers once it has the data. */
	status = wait_status("\"local_address\"", 25);
	if (!status)
		fail("status never reported a local host");
	if (!strstr(status, "\"peers\":{\"") || !strstr(status, "\"address\":\"fd"))
		fail("status has no peers with derived addresses");
	if (!strstr(status, "\"local_pubkey\":\""))
		fail("status has no local_pubkey");
	if (!strstr(status, "\"pex_socket\":"))
		fail("status has no pex_socket flag");
	/*
	 * The global PEX socket is AF_INET6; a kernel booted with ipv6.disable=1
	 * cannot create it, and then there is nothing to protect. CI has IPv6.
	 */
	pex_socket = strstr(status, "\"pex_socket\":true") != NULL;
	printf("round %d status: %s\n", round, status);
	free(status);

	pthread_mutex_lock(&lock);
	if (updates_up <= up_before)
		fail("no link-up update delivered");
	if (!last_update || !strstr(last_update, "\"ip6addr\":[{\"mask\":\"64\",\"ipaddr\":\"fd"))
		fail("update did not carry the /64 local address");
	if (!strstr(last_update, "\"routes\":[") || !strstr(last_update, "192.168."))
		fail("update did not carry the configured IPv4 routes");
	if (pex_socket && protects <= protects_before)
		fail("the global PEX socket was not offered for protect()");
	if (!pex_socket)
		printf("round %d: no global PEX socket (no IPv6 here), protect() check skipped\n", round);
	printf("round %d update: %s\n", round, last_update);
	pthread_mutex_unlock(&lock);

	key = unetd_core_status_json();
	if (!key)
		fail("status unavailable");
	free(key);

	/* CORE_TEST_HOLD=<seconds>: stay up in round 1 so another process (unet-dht)
	 * can connect to the control socket and use the relay. */
	if (round == 1 && getenv("CORE_TEST_HOLD")) {
		printf("round %d: holding for %s s\n", round, getenv("CORE_TEST_HOLD"));
		fflush(stdout);
		sleep(atoi(getenv("CORE_TEST_HOLD")));
	}

	if (unetd_core_network_remove("no-such-network") == 0)
		fail("removing an unknown network succeeded");
	if (unetd_core_network_remove(getenv("NET_NAME") ? getenv("NET_NAME") : "wgm1"))
		fail("network_remove");

	pthread_mutex_lock(&lock);
	if (!last_update || !strstr(last_update, "\"link-up\":false"))
		fail("no link-down update on remove");
	pthread_mutex_unlock(&lock);

	unetd_core_stop();
	if (unetd_core_running())
		fail("still running after stop");
	if (unetd_core_network_add(json) != -107 /* -ENOTCONN */)
		fail("network_add after stop should fail with -ENOTCONN");
	printf("round %d ok\n", round);
}

int main(int argc, char **argv)
{
	struct unetd_core_config cfg;
	struct unetd_core_callbacks cb = {
		.protect_socket = on_protect,
		.network_update = on_update,
	};
	char priv[UNETD_KEY_B64_LEN], pub[UNETD_KEY_B64_LEN], pub2[UNETD_KEY_B64_LEN];
	char *tail;

	if (argc != 4) {
		fprintf(stderr, "usage: %s <data-dir> <socket-dir> <network-json>\n", argv[0]);
		return 2;
	}
	cfg = (struct unetd_core_config){
		.data_dir = argv[1],
		.socket_dir = argv[2],
		/* CORE_TEST_UNIX_SOCKET: also serve unetd's control socket (unet-dht relay) */
		.unix_socket = getenv("CORE_TEST_UNIX_SOCKET"),
		.pex_port = 0,
		.debug = true,
	};

	if (unetd_core_generate_key(priv, sizeof(priv), pub, sizeof(pub)))
		fail("generate_key");
	if (unetd_core_public_key(priv, pub2, sizeof(pub2)) || strcmp(pub, pub2))
		fail("public_key does not match generate_key");
	if (strlen(priv) != 44 || strlen(pub) != 44)
		fail("keys are not 44-character base64");
	if (unetd_core_public_key("not a key", pub2, sizeof(pub2)) == 0)
		fail("bad private key accepted");

	unetd_log_capture_start(true);

	one_cycle(&cfg, &cb, argv[3], 1);
	one_cycle(&cfg, &cb, argv[3], 2);

	tail = unetd_log_tail(5);
	if (!tail || !*tail)
		fail("log ring is empty");
	free(tail);
	check_log_since();

	printf("PASS: core start/add/status/remove/stop twice in one process\n");
	usleep(300 * 1000);	/* let the capture thread echo the line before exit */
	return 0;
}
