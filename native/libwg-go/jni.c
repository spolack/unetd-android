//go:build android

/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI glue for libwg-go, modelled on wireguard-android's jni.c
 * (Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>, Apache-2.0).
 *
 * Bound to org.unetd.android.nativebridge.WgGo. Beyond upstream it carries the
 * protect() bridge: Go calls wg_protect_fd() whenever the device opens a UDP
 * socket (bind.go), and this file turns that into VpnService.protect(fd) on
 * whatever thread the bind happens to run on.
 */

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string socket_dir, struct go_string uapi_name, int tun_fd);
extern void wgTurnOff(int handle);
extern char *wgVersion(void);

static JavaVM *jvm;
static pthread_mutex_t protector_lock = PTHREAD_MUTEX_INITIALIZER;
static jobject protector;        /* global ref; any object with boolean protect(int) */
static jmethodID protect_method;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	jvm = vm;
	return JNI_VERSION_1_6;
}

/*
 * Called from Go, on a Go-owned thread or on the JNI caller's thread. Without
 * a protector (none set, or cleared at teardown) the socket is left alone and
 * reported as fine: that is the host-test situation, never a phone's.
 */
bool wg_protect_fd(int fd)
{
	JNIEnv *env = NULL;
	jboolean ok = JNI_FALSE;
	bool have_protector;
	int attached = 0;
	jint st;

	pthread_mutex_lock(&protector_lock);
	have_protector = jvm && protector;
	if (!have_protector)
		goto out;

	st = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);
	if (st == JNI_EDETACHED) {
		if ((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK)
			goto out;
		attached = 1;
	} else if (st != JNI_OK) {
		goto out;
	}

	ok = (*env)->CallBooleanMethod(env, protector, protect_method, (jint)fd);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		ok = JNI_FALSE;
	}
	if (!ok)
		__android_log_print(ANDROID_LOG_ERROR, "libwg-go", "protect(%d) refused", fd);
	fprintf(stderr, "wg: VpnService.protect(%d) -> %s\n", fd, ok ? "ok" : "REFUSED");

	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
out:
	pthread_mutex_unlock(&protector_lock);
	return !have_protector || ok;
}

static struct go_string jstring_to_go(JNIEnv *env, jstring s, const char **keep)
{
	struct go_string gs = { .str = "", .n = 0 };

	*keep = NULL;
	if (!s)
		return gs;
	*keep = (*env)->GetStringUTFChars(env, s, 0);
	gs.str = *keep;
	gs.n = (long)(*env)->GetStringUTFLength(env, s);
	return gs;
}

static void release(JNIEnv *env, jstring s, const char *keep)
{
	if (keep)
		(*env)->ReleaseStringUTFChars(env, s, keep);
}

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgSetProtector(JNIEnv *env, jobject thiz, jobject obj)
{
	pthread_mutex_lock(&protector_lock);
	if (protector) {
		(*env)->DeleteGlobalRef(env, protector);
		protector = NULL;
		protect_method = NULL;
	}
	if (obj) {
		jclass cls = (*env)->GetObjectClass(env, obj);
		protect_method = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
		if (protect_method)
			protector = (*env)->NewGlobalRef(env, obj);
		else
			(*env)->ExceptionClear(env);
	}
	pthread_mutex_unlock(&protector_lock);
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgTurnOn(JNIEnv *env, jobject thiz, jstring socket_dir, jstring name, jint tun_fd)
{
	const char *keep_dir, *keep_name;
	struct go_string gdir = jstring_to_go(env, socket_dir, &keep_dir);
	struct go_string gname = jstring_to_go(env, name, &keep_name);
	int ret = wgTurnOn(gdir, gname, tun_fd);

	release(env, socket_dir, keep_dir);
	release(env, name, keep_name);
	return ret;
}

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgTurnOff(JNIEnv *env, jobject thiz, jint handle)
{
	wgTurnOff(handle);
}

JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgVersion(JNIEnv *env, jobject thiz)
{
	char *version = wgVersion();
	jstring ret;

	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}
