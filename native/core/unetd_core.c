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
#include <unistd.h>

#include <libubox/uloop.h>
#include <libubox/blobmsg_json.h>
#include <libubox/utils.h>

#include "unetd.h"
#include "unetd_core.h"

/* ---- what upstream main.c provided ------------------------------------- */

const char *mssfix_path = UNETD_MSS_BPF_PATH;
const char *data_dir = UNETD_DATA_DIR;
int global_pex_port = UNETD_GLOBAL_PEX_PORT;

static bool debug;

bool unetd_debug_active(void)
{
	return debug;
}

void unetd_debug_printf(const char *format, ...)
{
	va_list ap;

	if (!debug)
		return;

	va_start(ap, format);
	vfprintf(stderr, format, ap);
	va_end(ap);
}

/* No hosts file: the app reads names and addresses from the status dump. */
void unetd_write_hosts(void)
{
}

/* ---- the thread and its command channel -------------------------------- */

enum cmd_type {
	CMD_NETWORK_ADD,
	CMD_NETWORK_REMOVE,
	CMD_STATUS,
	CMD_STOP,
};

struct cmd {
	enum cmd_type type;
	const char *arg;
	int ret;
	char *out;
	bool done;
};

static struct {
	pthread_mutex_t api_lock;	/* one caller at a time */
	pthread_mutex_t lock;		/* protects the fields below */
	pthread_cond_t cond;

	pthread_t thread;
	bool thread_alive;
	bool running;			/* uloop up and accepting commands */
	bool started;			/* startup handshake */

	struct cmd *cmd;
	int efd;
	struct uloop_fd cmd_fd;

	struct unetd_core_callbacks cb;
	char *data_dir;
	char *socket_dir;
	char *unix_socket;
	int pex_port;
	bool debug;
	bool pex_ok;
} core = {
	.api_lock = PTHREAD_MUTEX_INITIALIZER,
	.lock = PTHREAD_MUTEX_INITIALIZER,
	.cond = PTHREAD_COND_INITIALIZER,
	.efd = -1,
};

static void platform_protect_socket(int fd)
{
	if (core.cb.protect_socket)
		core.cb.protect_socket(core.cb.priv, fd);
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

static const struct unetd_platform_ops platform_ops = {
	.protect_socket = platform_protect_socket,
	.network_update = platform_network_update,
};

static int do_network_add(const char *json)
{
	static struct blob_buf b;
	struct blob_attr *name;
	int ret;

	blob_buf_init(&b, 0);
	if (!blobmsg_add_json_from_string(&b, json)) {
		fprintf(stderr, "unetd: network config is not valid JSON\n");
		ret = -EINVAL;
		goto out;
	}

	blobmsg_parse(&network_policy[NETWORK_ATTR_NAME], 1, &name,
		      blobmsg_data(b.head), blobmsg_len(b.head));
	if (!name) {
		fprintf(stderr, "unetd: network config has no name\n");
		ret = -EINVAL;
		goto out;
	}

	ret = unetd_network_add(blobmsg_get_string(name), b.head);
	if (ret)
		fprintf(stderr, "unetd: network_add(%s) failed\n", blobmsg_get_string(name));
out:
	blob_buf_free(&b);
	return ret;
}

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

static void cmd_fd_cb(struct uloop_fd *fd, unsigned int events)
{
	struct cmd *cmd;
	uint64_t v;

	while (read(fd->fd, &v, sizeof(v)) > 0)
		;

	pthread_mutex_lock(&core.lock);
	cmd = core.cmd;
	pthread_mutex_unlock(&core.lock);
	if (!cmd)
		return;

	switch (cmd->type) {
	case CMD_NETWORK_ADD:
		cmd->ret = do_network_add(cmd->arg);
		break;
	case CMD_NETWORK_REMOVE:
		cmd->ret = unetd_network_remove(cmd->arg);
		break;
	case CMD_STATUS:
		cmd->out = do_status();
		cmd->ret = cmd->out ? 0 : -ENOMEM;
		break;
	case CMD_STOP:
		cmd->ret = 0;
		uloop_end();
		break;
	}

	pthread_mutex_lock(&core.lock);
	cmd->done = true;
	core.cmd = NULL;
	pthread_cond_broadcast(&core.cond);
	pthread_mutex_unlock(&core.lock);
}

static int run_cmd(struct cmd *cmd)
{
	uint64_t one = 1;
	int ret;

	pthread_mutex_lock(&core.api_lock);
	pthread_mutex_lock(&core.lock);
	if (!core.running) {
		ret = -ENOTCONN;
		goto out;
	}

	core.cmd = cmd;
	if (write(core.efd, &one, sizeof(one)) != sizeof(one)) {
		core.cmd = NULL;
		ret = -EIO;
		goto out;
	}

	while (!cmd->done && core.running)
		pthread_cond_wait(&core.cond, &core.lock);
	ret = cmd->done ? cmd->ret : -ENOTCONN;
out:
	pthread_mutex_unlock(&core.lock);
	pthread_mutex_unlock(&core.api_lock);
	return ret;
}

static void *core_thread(void *arg)
{
	struct sigaction sa_int, sa_term;

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

	core.pex_ok = global_pex_open(core.unix_socket) == 0;
	if (!core.pex_ok)
		fprintf(stderr, "unetd: failed to open global PEX port %d: %s\n",
			global_pex_port, strerror(errno));

	pthread_mutex_lock(&core.lock);
	core.running = true;
	core.started = true;
	pthread_cond_broadcast(&core.cond);
	pthread_mutex_unlock(&core.lock);

	uloop_run();

	network_free_all();
	pex_close();
	uloop_fd_delete(&core.cmd_fd);
	uloop_done();
	unetd_platform = NULL;

	pthread_mutex_lock(&core.lock);
	core.running = false;
	if (core.cmd) {
		core.cmd->done = true;
		core.cmd->ret = -ECANCELED;
		core.cmd = NULL;
	}
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

int unetd_core_start(const struct unetd_core_config *cfg,
		     const struct unetd_core_callbacks *cb)
{
	int ret = 0;

	if (!cfg || !cfg->data_dir || !cfg->socket_dir)
		return -EINVAL;

	pthread_mutex_lock(&core.api_lock);
	if (core.thread_alive) {
		ret = -EALREADY;
		goto out;
	}

	core.cb = cb ? *cb : (struct unetd_core_callbacks){};
	core.data_dir = strdup(cfg->data_dir);
	core.socket_dir = strdup(cfg->socket_dir);
	core.unix_socket = cfg->unix_socket ? strdup(cfg->unix_socket) : NULL;
	core.pex_port = cfg->pex_port > 0 ? cfg->pex_port : UNETD_GLOBAL_PEX_PORT;
	core.debug = cfg->debug;
	mkdir_p(core.data_dir, 0700);
	mkdir_p(core.socket_dir, 0700);
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
	pthread_mutex_unlock(&core.lock);
out:
	pthread_mutex_unlock(&core.api_lock);
	return ret;
}

void unetd_core_stop(void)
{
	struct cmd cmd = { .type = CMD_STOP };

	run_cmd(&cmd);

	pthread_mutex_lock(&core.api_lock);
	if (core.thread_alive) {
		pthread_join(core.thread, NULL);
		core.thread_alive = false;
	}
	if (core.efd >= 0) {
		close(core.efd);
		core.efd = -1;
	}
	if (core.unix_socket)
		unlink(core.unix_socket);
	free_config();
	pthread_mutex_unlock(&core.api_lock);
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
	struct cmd cmd = { .type = CMD_NETWORK_ADD, .arg = json };

	if (!json)
		return -EINVAL;
	return run_cmd(&cmd);
}

int unetd_core_network_remove(const char *name)
{
	struct cmd cmd = { .type = CMD_NETWORK_REMOVE, .arg = name };

	if (!name)
		return -EINVAL;
	return run_cmd(&cmd);
}

char *unetd_core_status_json(void)
{
	struct cmd cmd = { .type = CMD_STATUS };

	if (run_cmd(&cmd) < 0)
		return NULL;
	return cmd.out;
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

	if (random_bytes(priv, sizeof(priv)) < 0)
		return -1;
	curve25519_clamp_secret(priv);
	curve25519_generate_public(pub, priv);

	if (b64_encode(priv, sizeof(priv), priv_b64, priv_len) < 0 ||
	    b64_encode(pub, sizeof(pub), pub_b64, pub_len) < 0)
		return -1;
	return 0;
}

int unetd_core_public_key(const char *priv_b64, char *pub_b64, size_t pub_len)
{
	uint8_t priv[CURVE25519_KEY_SIZE], pub[CURVE25519_KEY_SIZE];

	if (!priv_b64 || b64_decode(priv_b64, priv, sizeof(priv)) != sizeof(priv))
		return -1;
	curve25519_generate_public(pub, priv);
	if (b64_encode(pub, sizeof(pub), pub_b64, pub_len) < 0)
		return -1;
	return 0;
}
