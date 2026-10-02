/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI glue for libwg-go, modelled on wireguard-android's jni.c
 * (Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>, Apache-2.0).
 *
 * Bound to org.unetd.android.nativebridge.WgGo. Beyond upstream it carries the
 * protect() bridge: Go calls wg_protect_fd() from its bind-time control
 * function, and this file turns that into VpnService.protect(fd) on whatever
 * thread the bind happens to run on.
 */

#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern void wgSetSocketDirectory(struct go_string dir);
extern int wgTurnOn(struct go_string uapi_name, int tun_fd, struct go_string settings);
extern void wgTurnOff(int handle);
extern int wgGetSocketV4(int handle);
extern int wgGetSocketV6(int handle);
extern char *wgGetConfig(int handle);
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

/* Called from Go, on a Go-owned thread or on the JNI caller's thread. */
void wg_protect_fd(int fd)
{
	JNIEnv *env = NULL;
	jboolean ok = JNI_FALSE;
	int attached = 0;
	jint st;

	pthread_mutex_lock(&protector_lock);
	if (!jvm || !protector)
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
	if (!ok) {
		__android_log_print(ANDROID_LOG_ERROR, "libwg-go", "protect(%d) failed", fd);
		fprintf(stderr, "wg: VpnService.protect(%d) refused\n", fd);
	}

	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
out:
	pthread_mutex_unlock(&protector_lock);
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

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgSetSocketDirectory(JNIEnv *env, jobject thiz, jstring dir)
{
	const char *keep;
	struct go_string gs = jstring_to_go(env, dir, &keep);

	wgSetSocketDirectory(gs);
	release(env, dir, keep);
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgTurnOn(JNIEnv *env, jobject thiz, jstring name, jint tun_fd, jstring settings)
{
	const char *keep_name, *keep_settings;
	struct go_string gname = jstring_to_go(env, name, &keep_name);
	struct go_string gsettings = jstring_to_go(env, settings, &keep_settings);
	int ret = wgTurnOn(gname, tun_fd, gsettings);

	release(env, name, keep_name);
	release(env, settings, keep_settings);
	return ret;
}

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgTurnOff(JNIEnv *env, jobject thiz, jint handle)
{
	wgTurnOff(handle);
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgGetSocketV4(JNIEnv *env, jobject thiz, jint handle)
{
	return wgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgGetSocketV6(JNIEnv *env, jobject thiz, jint handle)
{
	return wgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_WgGo_wgGetConfig(JNIEnv *env, jobject thiz, jint handle)
{
	char *config = wgGetConfig(handle);
	jstring ret;

	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
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
