/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UNETD_CORE_H
#define UNETD_CORE_H

#include <stdbool.h>
#include <stddef.h>

/*
 * unetd as a library.
 *
 * Replaces upstream main.c: the daemon's uloop runs on a thread owned by this
 * module, and everything else talks to it through the functions below, which
 * marshal onto that thread. No unetd function may be called from any other
 * thread -- its avl/vlist trees are mutated from uloop timers without locks.
 *
 * Callbacks run on the uloop thread. thread_started/thread_stopping bracket its
 * lifetime so a JNI embedder can attach it to the JVM once.
 */

struct unetd_core_config {
	const char *data_dir;		/* where <network>.bin caches live */
	const char *socket_dir;		/* WireGuard UAPI sockets: <dir>/<network>.sock */
	const char *unix_socket;	/* control socket for unet-dht, or NULL */
	int pex_port;			/* global PEX port, 0 for the default (51819) */
	bool debug;
};

struct unetd_core_callbacks {
	void *priv;
	void (*thread_started)(void *priv);
	void (*thread_stopping)(void *priv);
	/* A socket that must bypass the tunnel (see platform.h in unetd). */
	void (*protect_socket)(void *priv, int fd);
	/* The interface update unetd would pass to update-cmd, as JSON. */
	void (*network_update)(void *priv, const char *json);
};

/* Starts the uloop thread and opens the global PEX socket. 0 on success. */
int unetd_core_start(const struct unetd_core_config *cfg,
		     const struct unetd_core_callbacks *cb);
/* Tears every network down, stops the thread and joins it. */
void unetd_core_stop(void);
bool unetd_core_running(void);

/* Same JSON as unetd's -N option / ubus network_add. Synchronous. */
int unetd_core_network_add(const char *json);
int unetd_core_network_remove(const char *name);

/* Status of every network as JSON (malloc'd, caller frees), or NULL. */
char *unetd_core_status_json(void);

/* Curve25519 helpers for the setup UI; no uloop needed. Base64, 45-byte buffers. */
#define UNETD_KEY_B64_LEN 45
int unetd_core_generate_key(char *priv_b64, size_t priv_len, char *pub_b64, size_t pub_len);
int unetd_core_public_key(const char *priv_b64, char *pub_b64, size_t pub_len);

#endif
