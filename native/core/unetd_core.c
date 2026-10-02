/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <libubox/uloop.h>
#include <libubox/blobmsg_json.h>
#include <libubox/utils.h>

#include "unetd.h"
#include "udht.h"
#include "unetd_core.h"
#include "unetd_log.h"

/* ---- what upstream main.c provided ------------------------------------- */

const char *mssfix_path = UNETD_MSS_BPF_PATH;
const char *data_dir = UNETD_DATA_DIR;
int global_pex_port = UNETD_GLOBAL_PEX_PORT;

static bool debug;

bool unetd_debug_active(void)
{
	return debug;
}

/* unetd's D() trace goes straight into the log ring, no pipe in between. */
void unetd_debug_printf(const char *format, ...)
{
	char buf[1024];
	va_list ap;

	if (!debug)
		return;

	va_start(ap, format);
	vsnprintf(buf, sizeof(buf), format, ap);
	va_end(ap);
	unetd_log_push(buf);
}

/* Our own diagnostics, same destination. */
static void __attribute__((format(printf, 1, 2))) core_log(const char *format, ...)
{
	char buf[1024];
	va_list ap;

	va_start(ap, format);
	vsnprintf(buf, sizeof(buf), format, ap);
	va_end(ap);
	unetd_log_push(buf);
}

/* No hosts file: the app reads names and addresses from the status dump. */
void unetd_write_hosts(void)
{
}

/* ---- the thread and its command channel -------------------------------- */

enum cmd_type {
	CMD_NETWORK_ADD,
	CMD_NETWORK_REMOVE,
	CMD_DHT_START,
	CMD_DHT_STOP,
	CMD_STOP,
};

/* A deep copy of unetd_core_dht_config, owned by the command. */
struct dht_args {
	struct udht_config cfg;
	char *strings[2 + 64 + 16];
	const char *keys[64];
	const char *bootstrap[16];
};

struct cmd {
	enum cmd_type type;
	char *arg;
	struct dht_args *dht;
	int ret;
	bool done;
};

static void dht_args_free(struct dht_args *a)
{
	size_t i;

	if (!a)
		return;
	for (i = 0; i < sizeof(a->strings) / sizeof(a->strings[0]); i++)
		free(a->strings[i]);
	free(a);
}

static struct dht_args *dht_args_copy(const struct unetd_core_dht_config *cfg)
{
	struct dht_args *a = calloc(1, sizeof(*a));
	size_t n = 0;
	int i;

	if (!a)
		return NULL;
	a->cfg.id_string = a->strings[n++] = strdup(cfg->id_string);
	if (cfg->node_file)
		a->cfg.node_file = a->strings[n++] = strdup(cfg->node_file);
	a->cfg.auth_keys = a->keys;
	for (i = 0; i < cfg->n_auth_keys && i < 64; i++)
		a->keys[a->cfg.n_auth_keys++] = a->strings[n++] = strdup(cfg->auth_keys[i]);
	a->cfg.bootstrap = a->bootstrap;
	for (i = 0; i < cfg->n_bootstrap && i < 16; i++)
		a->bootstrap[a->cfg.n_bootstrap++] = a->strings[n++] = strdup(cfg->bootstrap[i]);
	a->cfg.debug = cfg->debug;
	a->cfg.keep_running = true;
	return a;
}

#define CMD_TIMEOUT_MS		30000	/* add/remove: the loop may be in a DNS lookup */
#define STOP_TIMEOUT_MS		10000
#define STATUS_INTERVAL_MS	1000

static struct {
	pthread_mutex_t api_lock;	/* one caller at a time */
	pthread_mutex_t lock;		/* protects the fields below */
	pthread_cond_t cond;

	pthread_t thread;
	bool thread_alive;		/* created and not yet joined */
	bool thread_done;		/* core_thread() has returned */
	bool running;			/* uloop up and accepting commands */
	bool started;			/* startup handshake */
	int start_error;		/* negative errno when startup failed */

	/*
	 * The one command in flight. Static, not on a caller's stack, so a
	 * caller that gave up waiting (the loop is stuck) leaves nothing
	 * dangling for the loop to write to later.
	 */
	struct cmd pending;
	bool cmd_posted;
	int efd;
	struct uloop_fd cmd_fd;

	char *status;			/* the latest snapshot, JSON */
	struct uloop_timeout status_timer;
	bool dht_running;		/* uloop thread only */

	struct unetd_core_callbacks cb;
	char *data_dir;
	char *socket_dir;
	char *unix_socket;
	int pex_port;
	bool debug;
} core = {
	.api_lock = PTHREAD_MUTEX_INITIALIZER,
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.cond = PTHREAD_COND_INITIALIZER,
	.efd = -1,
};

/* ---- the status snapshot (uloop thread) ---------------------------------- */

static char *do_status(void)
{
	static struct blob_buf b;
	char pubkey[UNETD_KEY_B64_LEN];
	struct network_peer *peer;
	struct network *net;
	void *c, *n, *p;
	char *json;

	blob_buf_init(&b, 0);
	blobmsg_add_u8(&b, "pex_socket", pex_socket() >= 0);
	blobmsg_add_u32(&b, "pex_port", global_pex_port);
	c = blobmsg_open_table(&b, "dht");
	udht_status(&b);
	blobmsg_close_table(&b, c);

	c = blobmsg_open_table(&b, "networks");
	avl_for_each_element(&networks, net, node) {
		n = blobmsg_open_table(&b, network_name(net));
		network_dump_status(&b, net);
		if (b64_encode(net->config.pubkey, CURVE25519_KEY_SIZE, pubkey, sizeof(pubkey)) > 0)
			blobmsg_add_string(&b, "local_pubkey", pubkey);
		blobmsg_add_u64(&b, "net_data_version", net->net_data_version);
		blobmsg_add_u32(&b, "keepalive", net->net_config.keepalive);
		blobmsg_add_u32(&b, "port", net->net_config.port);
		blobmsg_add_u8(&b, "pex_open", net->pex.fd.fd >= 0);
		blobmsg_add_u8(&b, "stun", !list_empty(&net->stun.servers));
		/* what STUN learned: the NAT's outside port for the WireGuard port
		 * ("data") and for the PEX port ("auth"); 0 until a server answered */
		blobmsg_add_u32(&b, "stun_port_ext", net->stun.port_ext);
		blobmsg_add_u32(&b, "stun_auth_port_ext", net->stun.auth_port_ext);
		/* network_dump_status() does not say which peers are reached via a gateway */
		p = blobmsg_open_array(&b, "indirect_peers");
		vlist_for_each_element(&net->peers, peer, node)
			if (peer->indirect)
				blobmsg_add_string(&b, NULL, network_peer_name(peer));
		blobmsg_close_array(&b, p);
		blobmsg_close_table(&b, n);
	}
	blobmsg_close_table(&b, c);

	json = blobmsg_format_json(b.head, true);
	blob_buf_free(&b);
	return json;
}

static void status_refresh(void)
{
	char *json = do_status();

	if (!json)
		return;
	pthread_mutex_lock(&core.lock);
	free(core.status);
	core.status = json;
	pthread_mutex_unlock(&core.lock);

	/* Counters move while a network or the DHT node exists; nothing moves without. */
	if (!avl_is_empty(&networks) || core.dht_running)
		uloop_timeout_set(&core.status_timer, STATUS_INTERVAL_MS);
}

static void status_timer_cb(struct uloop_timeout *t)
{
	status_refresh();
}

/* ---- platform hooks (uloop thread) --------------------------------------- */

static bool protect_refused;	/* during start-up: the reason it failed */

static bool platform_protect_socket(int fd)
{
	bool ok;

	if (!core.cb.protect_socket)
		return true;
	ok = core.cb.protect_socket(core.cb.priv, fd);
	if (!ok)
		protect_refused = true;
	return ok;
}

static void platform_network_update(struct network *net, struct blob_attr *data)
{
	char *json;

	if (!core.cb.network_update)
		return;

	json = blobmsg_format_json(data, true);
	if (!json)
		return;
	core.cb.network_update(core.cb.priv, json);
	free(json);
}

static void platform_event(enum unetd_platform_event ev, struct network *net,
			   const char *peer_name)
{
	static const enum unetd_core_event map[] = {
		[UNETD_EV_PEER_UP] = UNETD_CORE_EV_PEER_UP,
		[UNETD_EV_PEER_DOWN] = UNETD_CORE_EV_PEER_DOWN,
		[UNETD_EV_NETWORK_RELOAD] = UNETD_CORE_EV_NETWORK_RELOAD,
		[UNETD_EV_STUN_PORT] = UNETD_CORE_EV_STUN_PORT,
	};

	status_refresh();
	if (core.cb.event)
		core.cb.event(core.cb.priv, map[ev], net ? network_name(net) : NULL, peer_name);
}

static const struct unetd_platform_ops platform_ops = {
	.protect_socket = platform_protect_socket,
	.network_update = platform_network_update,
	.event = platform_event,
};

/* ---- commands (uloop thread) ---------------------------------------------- */

static int do_network_add(const char *json)
{
	static struct blob_buf b;
	struct blob_attr *name;
	int ret;

	blob_buf_init(&b, 0);
	if (!blobmsg_add_json_from_string(&b, json)) {
		core_log("unetd: network config is not valid JSON");
		ret = -EINVAL;
		goto out;
	}

	blobmsg_parse(&network_policy[NETWORK_ATTR_NAME], 1, &name,
		      blobmsg_data(b.head), blobmsg_len(b.head));
	if (!name) {
		core_log("unetd: network config has no name");
		ret = -EINVAL;
		goto out;
	}

	ret = unetd_network_add(blobmsg_get_string(name), b.head);
	if (ret)
		core_log("unetd: network_add(%s) failed", blobmsg_get_string(name));
out:
	blob_buf_free(&b);
	return ret;
}

static void cmd_fd_cb(struct uloop_fd *fd, unsigned int events)
{
	struct cmd *cmd = &core.pending;
	bool posted;
	uint64_t v;

	while (read(fd->fd, &v, sizeof(v)) > 0)
		;

	pthread_mutex_lock(&core.lock);
	posted = core.cmd_posted;
	pthread_mutex_unlock(&core.lock);
	if (!posted)
		return;

	switch (cmd->type) {
	case CMD_NETWORK_ADD:
		cmd->ret = do_network_add(cmd->arg);
		break;
	case CMD_NETWORK_REMOVE:
		cmd->ret = unetd_network_remove(cmd->arg);
		break;
	case CMD_DHT_START:
		if (!core.unix_socket) {
			cmd->ret = -ENOTSUP;
			break;
		}
		cmd->dht->cfg.unix_path = core.unix_socket;
		cmd->ret = udht_setup(&cmd->dht->cfg) < 0 ? -EIO : 0;
		core.dht_running = cmd->ret == 0;
		dht_args_free(cmd->dht);
		cmd->dht = NULL;
		break;
	case CMD_DHT_STOP:
		udht_stop();
		core.dht_running = false;
		cmd->ret = 0;
		break;
	case CMD_STOP:
		cmd->ret = 0;
		uloop_end();
		break;
	}
	status_refresh();

	pthread_mutex_lock(&core.lock);
	cmd->done = true;
	core.cmd_posted = false;
	pthread_cond_broadcast(&core.cond);
	pthread_mutex_unlock(&core.lock);
}

/* Waits for cond with an absolute deadline; returns false on timeout. */
static bool wait_until(const struct timespec *deadline)
{
	return pthread_cond_timedwait(&core.cond, &core.lock, deadline) != ETIMEDOUT;
}

static void deadline_in(struct timespec *ts, int ms)
{
	clock_gettime(CLOCK_REALTIME, ts);
	ts->tv_sec += ms / 1000;
	ts->tv_nsec += (long)(ms % 1000) * 1000000L;
	if (ts->tv_nsec >= 1000000000L) {
		ts->tv_sec++;
		ts->tv_nsec -= 1000000000L;
	}
}

/*
 * Runs a command on the uloop thread and waits for it, at most timeout_ms.
 * On a timeout the command stays posted and is still executed when the loop
 * gets to it; until then every further command is refused with -EBUSY.
 */
static int run_cmd(enum cmd_type type, const char *arg, struct dht_args *dht, int timeout_ms)
{
	struct timespec deadline;
	uint64_t one = 1;
	int ret;

	pthread_mutex_lock(&core.api_lock);
	pthread_mutex_lock(&core.lock);
	if (!core.running) {
		ret = -ENOTCONN;
		goto out;
	}
	if (core.cmd_posted) {
		ret = -EBUSY;
		goto out;
	}

	free(core.pending.arg);
	dht_args_free(core.pending.dht);
	core.pending = (struct cmd){ .type = type, .arg = arg ? strdup(arg) : NULL, .dht = dht };
	dht = NULL;
	core.cmd_posted = true;
	if (write(core.efd, &one, sizeof(one)) != sizeof(one)) {
		core.cmd_posted = false;
		ret = -EIO;
		goto out;
	}

	deadline_in(&deadline, timeout_ms);
	while (!core.pending.done && core.running) {
		if (!wait_until(&deadline)) {
			ret = -ETIMEDOUT;
			goto out;
		}
	}
	ret = core.pending.done ? core.pending.ret : -ENOTCONN;
out:
	pthread_mutex_unlock(&core.lock);
	pthread_mutex_unlock(&core.api_lock);
	dht_args_free(dht);	/* not posted */
	return ret;
}

/* ---- the thread ----------------------------------------------------------- */

static void *core_thread(void *arg)
{
	struct sigaction sa_int, sa_term;
	int err = 0;

	if (core.cb.thread_started)
		core.cb.thread_started(core.cb.priv);

	/*
	 * uloop_init() installs process-wide signal handlers. We never fork, so
	 * SIGCHLD stays with the host process (in a JVM, hijacking it is rude),
	 * and SIGINT/SIGTERM are restored right after. SIGPIPE being ignored is
	 * kept on purpose: wg-user.c writes to a unix socket that disappears
	 * when wireguard-go closes its listener.
	 */
	uloop_handle_sigchld = false;
	sigaction(SIGINT, NULL, &sa_int);
	sigaction(SIGTERM, NULL, &sa_term);
	uloop_init();
	sigaction(SIGINT, &sa_int, NULL);
	sigaction(SIGTERM, &sa_term, NULL);

	unetd_platform = &platform_ops;
	data_dir = core.data_dir;
	wg_user_socket_dir = core.socket_dir;
	global_pex_port = core.pex_port;
	debug = core.debug;

	core.cmd_fd.fd = core.efd;
	core.cmd_fd.cb = cmd_fd_cb;
	uloop_fd_add(&core.cmd_fd, ULOOP_READ);
	core.status_timer.cb = status_timer_cb;

	/*
	 * Without the global PEX socket there is no peer exchange, no network
	 * data and no DHT relay: not a degraded start but none at all.
	 */
	errno = 0;
	protect_refused = false;
	if (global_pex_open(core.unix_socket) < 0) {
		err = protect_refused ? -EPERM : errno ? -errno : -EIO;
		core_log("unetd: failed to open global PEX port %d: %s",
			 global_pex_port, strerror(-err));
	}

	if (!err) {
		status_refresh();
		pthread_mutex_lock(&core.lock);
		core.running = true;
		core.started = true;
		pthread_cond_broadcast(&core.cond);
		pthread_mutex_unlock(&core.lock);

		uloop_run();

		udht_stop();
		core.dht_running = false;
		network_free_all();
		status_refresh();	/* the empty snapshot, for a reader mid-stop */
		uloop_timeout_cancel(&core.status_timer);
	}

	pex_close();
	uloop_fd_delete(&core.cmd_fd);
	uloop_done();
	unetd_platform = NULL;
	data_dir = UNETD_DATA_DIR;
	wg_user_socket_dir = RUNSTATEDIR "/wireguard";

	pthread_mutex_lock(&core.lock);
	core.running = false;
	core.start_error = err;
	core.started = true;
	if (core.cmd_posted) {
		core.pending.done = true;
		core.pending.ret = -ECANCELED;
		core.cmd_posted = false;
	}
	free(core.status);
	core.status = NULL;
	core.thread_done = true;
	pthread_cond_broadcast(&core.cond);
	pthread_mutex_unlock(&core.lock);

	if (core.cb.thread_stopping)
		core.cb.thread_stopping(core.cb.priv);
	return NULL;
}

static void free_config(void)
{
	free(core.data_dir);
	free(core.socket_dir);
	free(core.unix_socket);
	core.data_dir = core.socket_dir = core.unix_socket = NULL;
}

/* Joins a finished thread. Called with api_lock held. */
static void reap_thread(void)
{
	pthread_join(core.thread, NULL);
	core.thread_alive = false;
	core.thread_done = false;
	if (core.efd >= 0) {
		close(core.efd);
		core.efd = -1;
	}
	if (core.unix_socket)
		unlink(core.unix_socket);
	free(core.pending.arg);
	core.pending.arg = NULL;
	dht_args_free(core.pending.dht);
	core.pending.dht = NULL;
	free_config();
}

int unetd_core_start(const struct unetd_core_config *cfg,
		     const struct unetd_core_callbacks *cb)
{
	bool done;
	int ret = 0;

	if (!cfg || !cfg->data_dir || !cfg->socket_dir)
		return -EINVAL;

	pthread_mutex_lock(&core.api_lock);
	if (core.thread_alive) {
		pthread_mutex_lock(&core.lock);
		done = core.thread_done;
		pthread_mutex_unlock(&core.lock);
		if (!done) {
			ret = -EALREADY;
			goto out;
		}
		reap_thread();	/* a stop that timed out, now finished */
	}

	core.cb = cb ? *cb : (struct unetd_core_callbacks){};
	core.data_dir = strdup(cfg->data_dir);
	core.socket_dir = strdup(cfg->socket_dir);
	core.unix_socket = cfg->unix_socket ? strdup(cfg->unix_socket) : NULL;
	core.pex_port = cfg->pex_port > 0 ? cfg->pex_port : UNETD_GLOBAL_PEX_PORT;
	core.debug = cfg->debug;
	if (mkdir_p(core.data_dir, 0700) < 0 || mkdir_p(core.socket_dir, 0700) < 0) {
		ret = -errno;
		free_config();
		goto out;
	}
	if (core.unix_socket)
		unlink(core.unix_socket);

	core.efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
	if (core.efd < 0) {
		ret = -errno;
		free_config();
		goto out;
	}

	core.started = false;
	core.running = false;
	core.thread_done = false;
	core.start_error = 0;
	core.cmd_posted = false;
	if (pthread_create(&core.thread, NULL, core_thread, NULL) != 0) {
		ret = -EAGAIN;
		close(core.efd);
		core.efd = -1;
		free_config();
		goto out;
	}
	core.thread_alive = true;

	pthread_mutex_lock(&core.lock);
	while (!core.started)
		pthread_cond_wait(&core.cond, &core.lock);
	ret = core.running ? 0 : core.start_error;
	pthread_mutex_unlock(&core.lock);
	if (ret)
		reap_thread();
out:
	pthread_mutex_unlock(&core.api_lock);
	return ret;
}

int unetd_core_stop(void)
{
	bool done;
	int ret;

	ret = run_cmd(CMD_STOP, NULL, NULL, STOP_TIMEOUT_MS);
	if (ret == -ETIMEDOUT)
		core_log("unetd: stop timed out, the event loop is stuck; it will finish on its own");

	pthread_mutex_lock(&core.api_lock);
	if (core.thread_alive) {
		pthread_mutex_lock(&core.lock);
		done = core.thread_done;
		if (!done && ret != -ETIMEDOUT) {
			/* the loop acknowledged the stop: its exit is bounded */
			while (!core.thread_done)
				pthread_cond_wait(&core.cond, &core.lock);
			done = true;
		}
		pthread_mutex_unlock(&core.lock);
		if (done) {
			reap_thread();
			ret = 0;
		}
	} else {
		ret = 0;
	}
	pthread_mutex_unlock(&core.api_lock);
	return ret;
}

bool unetd_core_running(void)
{
	bool running;

	pthread_mutex_lock(&core.lock);
	running = core.running;
	pthread_mutex_unlock(&core.lock);
	return running;
}

int unetd_core_network_add(const char *json)
{
	if (!json)
		return -EINVAL;
	return run_cmd(CMD_NETWORK_ADD, json, NULL, CMD_TIMEOUT_MS);
}

int unetd_core_network_remove(const char *name)
{
	if (!name)
		return -EINVAL;
	return run_cmd(CMD_NETWORK_REMOVE, name, NULL, CMD_TIMEOUT_MS);
}

int unetd_core_dht_start(const struct unetd_core_dht_config *cfg)
{
	struct dht_args *a;

	if (!cfg || !cfg->id_string)
		return -EINVAL;
	a = dht_args_copy(cfg);
	if (!a)
		return -ENOMEM;
	return run_cmd(CMD_DHT_START, NULL, a, CMD_TIMEOUT_MS);
}

int unetd_core_dht_stop(void)
{
	return run_cmd(CMD_DHT_STOP, NULL, NULL, CMD_TIMEOUT_MS);
}

char *unetd_core_status_json(void)
{
	char *copy;

	pthread_mutex_lock(&core.lock);
	copy = core.running && core.status ? strdup(core.status) : NULL;
	pthread_mutex_unlock(&core.lock);
	return copy;
}

/* ---- keys -------------------------------------------------------------- */

static int random_bytes(void *buf, size_t len)
{
	int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
	ssize_t n;

	if (fd < 0)
		return -1;
	n = read(fd, buf, len);
	close(fd);
	return n == (ssize_t)len ? 0 : -1;
}

int unetd_core_generate_key(char *priv_b64, size_t priv_len, char *pub_b64, size_t pub_len)
{
	uint8_t priv[CURVE25519_KEY_SIZE], pub[CURVE25519_KEY_SIZE];
	int ret = -1;

	if (random_bytes(priv, sizeof(priv)) < 0)
		return -1;
	curve25519_clamp_secret(priv);
	curve25519_generate_public(pub, priv);

	if (b64_encode(priv, sizeof(priv), priv_b64, priv_len) >= 0 &&
	    b64_encode(pub, sizeof(pub), pub_b64, pub_len) >= 0)
		ret = 0;
	memset(priv, 0, sizeof(priv));
	return ret;
}

int unetd_core_public_key(const char *priv_b64, char *pub_b64, size_t pub_len)
{
	uint8_t priv[CURVE25519_KEY_SIZE], pub[CURVE25519_KEY_SIZE];
	int ret = -1;

	if (!priv_b64 || b64_decode(priv_b64, priv, sizeof(priv)) != sizeof(priv))
		return -1;
	curve25519_generate_public(pub, priv);
	if (b64_encode(pub, sizeof(pub), pub_b64, pub_len) >= 0)
		ret = 0;
	memset(priv, 0, sizeof(priv));
	return ret;
}
