/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI binding for org.unetd.android.nativebridge.Udht.
 *
 * unet-dht is a separate process upstream and stays one here (Android
 * ":dht" process), because libubox's uloop is a process-wide singleton and
 * unetd already owns the one in the main process. udht_main() is the upstream
 * main() with its argv interface, see patches/unetd/0008.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/eventfd.h>

#include <libubox/uloop.h>

#include "udht.h"
#include "unetd_log.h"

extern int optind;

static int stop_efd = -1;
static struct uloop_fd stop_fd;

static void stop_cb(struct uloop_fd *fd, unsigned int events)
{
	uint64_t v;

	while (read(fd->fd, &v, sizeof(v)) > 0)
		;
	uloop_end();
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Udht_nativeRun(JNIEnv *env, jobject thiz,
	jstring unix_socket, jstring id_string, jstring node_file,
	jobjectArray auth_keys, jobjectArray bootstrap, jboolean debug)
{
	int nkeys = auth_keys ? (*env)->GetArrayLength(env, auth_keys) : 0;
	int nboot = bootstrap ? (*env)->GetArrayLength(env, bootstrap) : 0;
	int argc = 0, i, ret;
	char **argv;
	const char **keep;	/* GetStringUTFChars results to release */
	jstring *keep_js;
	int nkeep = 0;

	unetd_log_capture_start(false);

	argv = calloc(8 + 2 * nkeys + 2 * nboot, sizeof(*argv));
	keep = calloc(3 + nkeys + nboot, sizeof(*keep));
	keep_js = calloc(3 + nkeys + nboot, sizeof(*keep_js));
	if (!argv || !keep || !keep_js) {
		free(argv);
		free(keep);
		free(keep_js);
		return -1;
	}

#define KEEP(js) ({ const char *_s = (*env)->GetStringUTFChars(env, (js), NULL); \
		    keep_js[nkeep] = (js); keep[nkeep++] = _s; _s; })

	argv[argc++] = "unet-dht";
	if (debug)
		argv[argc++] = "-d";
	argv[argc++] = "-u";
	argv[argc++] = (char *)KEEP(unix_socket);
	if (node_file) {
		argv[argc++] = "-n";
		argv[argc++] = (char *)KEEP(node_file);
	}
	for (i = 0; i < nkeys; i++) {
		jstring k = (*env)->GetObjectArrayElement(env, auth_keys, i);

		argv[argc++] = "-N";
		argv[argc++] = (char *)KEEP(k);
	}
	/* -b replaces the public bootstrap routers (patches/unetd/0011) */
	for (i = 0; i < nboot; i++) {
		jstring b = (*env)->GetObjectArrayElement(env, bootstrap, i);

		argv[argc++] = "-b";
		argv[argc++] = (char *)KEEP(b);
	}
	argv[argc++] = (char *)KEEP(id_string);
	argv[argc] = NULL;
#undef KEEP

	/* getopt state from a previous run in this process */
	optind = 1;

	/*
	 * uloop_init() is idempotent, so initialising before udht_main() lets the
	 * stop descriptor be registered on the loop udht_main() will run.
	 */
	uloop_init();
	stop_efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
	stop_fd.fd = stop_efd;
	stop_fd.cb = stop_cb;
	uloop_fd_add(&stop_fd, ULOOP_READ);

	ret = udht_main(argc, argv);

	uloop_fd_delete(&stop_fd);
	close(stop_efd);
	stop_efd = -1;

	for (i = 0; i < nkeep; i++)
		(*env)->ReleaseStringUTFChars(env, keep_js[i], keep[i]);
	free(keep_js);
	free(keep);
	free(argv);
	return ret;
}

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_Udht_nativeRequestStop(JNIEnv *env, jobject thiz)
{
	uint64_t one = 1;

	if (stop_efd >= 0 && write(stop_efd, &one, sizeof(one)) < 0)
		; /* loop is already gone */
}
