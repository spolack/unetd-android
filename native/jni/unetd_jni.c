/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI binding for org.unetd.android.nativebridge.Unetd (a Kotlin object).
 *
 * Thin by design: every call goes straight to unetd_core.h, and the two
 * upcalls -- protectSocket() and onNetworkUpdate() -- come from the uloop
 * thread, which is attached to the JVM once for its whole lifetime.
 */
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "unetd_core.h"
#include "unetd_log.h"

static JavaVM *jvm;
static jobject callbacks;		/* global ref to an Unetd.Callbacks */
static jmethodID m_protect;		/* boolean protectSocket(int) */
static jmethodID m_update;		/* void onNetworkUpdate(String) */
static jmethodID m_event;		/* void onEvent(int, String, String) */
static JNIEnv *uloop_env;		/* valid between thread_started/stopping */
static pthread_t uloop_thread;		/* whose env that is */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	jvm = vm;
	return JNI_VERSION_1_6;
}

static JNIEnv *current_env(int *attached)
{
	JNIEnv *env = NULL;
	jint st;

	*attached = 0;
	if (uloop_env && pthread_equal(pthread_self(), uloop_thread))
		return uloop_env;
	st = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);
	if (st == JNI_OK)
		return env;
	if (st == JNI_EDETACHED && (*jvm)->AttachCurrentThread(jvm, &env, NULL) == JNI_OK) {
		*attached = 1;
		return env;
	}
	return NULL;
}

static void cb_thread_started(void *priv)
{
	uloop_thread = pthread_self();
	(*jvm)->AttachCurrentThread(jvm, &uloop_env, NULL);
}

static void cb_thread_stopping(void *priv)
{
	uloop_env = NULL;
	(*jvm)->DetachCurrentThread(jvm);
}

static bool cb_protect_socket(void *priv, int fd)
{
	int attached;
	JNIEnv *env = current_env(&attached);
	jboolean ok;
	char line[64];

	if (!env || !callbacks)
		return false;
	/* One line per socket, success included: on a phone with always-on and
	 * lockdown, every byte depends on this having worked. */
	ok = (*env)->CallBooleanMethod(env, callbacks, m_protect, (jint)fd);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		ok = JNI_FALSE;
		unetd_log_push("unetd: VpnService.protect() threw");
	}
	snprintf(line, sizeof(line), "unetd: VpnService.protect(%d) -> %s", fd, ok ? "ok" : "REFUSED");
	unetd_log_push(line);
	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
	return ok;
}

static void cb_event(void *priv, enum unetd_core_event ev, const char *network, const char *peer)
{
	int attached;
	JNIEnv *env = current_env(&attached);
	jstring n, p;

	if (!env || !callbacks)
		return;
	n = network ? (*env)->NewStringUTF(env, network) : NULL;
	p = peer ? (*env)->NewStringUTF(env, peer) : NULL;
	(*env)->CallVoidMethod(env, callbacks, m_event, (jint)ev, n, p);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
	if (n)
		(*env)->DeleteLocalRef(env, n);
	if (p)
		(*env)->DeleteLocalRef(env, p);
	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
}

static void cb_network_update(void *priv, const char *json)
{
	int attached;
	JNIEnv *env = current_env(&attached);
	jstring s;

	if (!env || !callbacks)
		return;
	s = (*env)->NewStringUTF(env, json);
	if (s) {
		(*env)->CallVoidMethod(env, callbacks, m_update, s);
		(*env)->DeleteLocalRef(env, s);
	}
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
	if (attached)
		(*jvm)->DetachCurrentThread(jvm);
}

static const struct unetd_core_callbacks core_callbacks = {
	.thread_started = cb_thread_started,
	.thread_stopping = cb_thread_stopping,
	.protect_socket = cb_protect_socket,
	.network_update = cb_network_update,
	.event = cb_event,
};

static char *dup_jstring(JNIEnv *env, jstring s)
{
	const char *utf;
	char *copy;

	if (!s)
		return NULL;
	utf = (*env)->GetStringUTFChars(env, s, NULL);
	if (!utf)
		return NULL;
	copy = strdup(utf);
	(*env)->ReleaseStringUTFChars(env, s, utf);
	return copy;
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeStart(JNIEnv *env, jobject thiz,
	jstring data_dir, jstring socket_dir, jstring unix_socket, jint pex_port,
	jboolean debug, jobject cb)
{
	char *d = dup_jstring(env, data_dir);
	char *s = dup_jstring(env, socket_dir);
	char *u = dup_jstring(env, unix_socket);
	struct unetd_core_config cfg = {
		.data_dir = d,
		.socket_dir = s,
		.unix_socket = u,
		.pex_port = pex_port,
		.debug = debug,
	};
	int ret;

	/* The running loop may be using the current callbacks: leave them alone. */
	if (unetd_core_running()) {
		ret = -EALREADY;
		goto out;
	}
	if (callbacks) {
		(*env)->DeleteGlobalRef(env, callbacks);
		callbacks = NULL;
	}
	if (cb) {
		jclass cls = (*env)->GetObjectClass(env, cb);

		m_protect = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
		m_update = (*env)->GetMethodID(env, cls, "onNetworkUpdate", "(Ljava/lang/String;)V");
		m_event = (*env)->GetMethodID(env, cls, "onEvent", "(ILjava/lang/String;Ljava/lang/String;)V");
		if (!m_protect || !m_update || !m_event) {
			(*env)->ExceptionClear(env);
			ret = -1;
			goto out;
		}
		callbacks = (*env)->NewGlobalRef(env, cb);
	}

	ret = unetd_core_start(&cfg, &core_callbacks);
out:
	free(d);
	free(s);
	free(u);
	return ret;
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeStop(JNIEnv *env, jobject thiz)
{
	int ret = unetd_core_stop();

	/* On a timeout the loop still runs and may call back: keep the ref. */
	if (ret == 0 && callbacks) {
		(*env)->DeleteGlobalRef(env, callbacks);
		callbacks = NULL;
	}
	return ret;
}

JNIEXPORT jboolean JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeRunning(JNIEnv *env, jobject thiz)
{
	return unetd_core_running();
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeNetworkAdd(JNIEnv *env, jobject thiz, jstring json)
{
	char *j = dup_jstring(env, json);
	int ret = unetd_core_network_add(j);

	free(j);
	return ret;
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeNetworkRemove(JNIEnv *env, jobject thiz, jstring name)
{
	char *n = dup_jstring(env, name);
	int ret = unetd_core_network_remove(n);

	free(n);
	return ret;
}

/* Copies a String[] into a NULL-terminated char*[]; free with free_strings(). */
static char **dup_jstrings(JNIEnv *env, jobjectArray arr, int *count)
{
	int n = arr ? (*env)->GetArrayLength(env, arr) : 0;
	char **out = calloc(n + 1, sizeof(*out));
	int i;

	*count = 0;
	if (!out)
		return NULL;
	for (i = 0; i < n; i++) {
		jstring js = (*env)->GetObjectArrayElement(env, arr, i);

		out[i] = dup_jstring(env, js);
		if (js)
			(*env)->DeleteLocalRef(env, js);
		if (!out[i])
			out[i] = strdup("");
	}
	*count = n;
	return out;
}

static void free_strings(char **v)
{
	char **p;

	if (!v)
		return;
	for (p = v; *p; p++)
		free(*p);
	free(v);
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeDhtStart(JNIEnv *env, jobject thiz,
	jstring id_string, jstring node_file, jobjectArray auth_keys, jobjectArray bootstrap,
	jboolean debug)
{
	char *id = dup_jstring(env, id_string);
	char *nf = dup_jstring(env, node_file);
	int nkeys, nboot;
	char **keys = dup_jstrings(env, auth_keys, &nkeys);
	char **boot = dup_jstrings(env, bootstrap, &nboot);
	struct unetd_core_dht_config cfg = {
		.id_string = id,
		.node_file = nf,
		.auth_keys = (const char *const *)keys,
		.n_auth_keys = nkeys,
		.bootstrap = (const char *const *)boot,
		.n_bootstrap = nboot,
		.debug = debug,
	};
	int ret = (id && keys && boot) ? unetd_core_dht_start(&cfg) : -ENOMEM;

	free(id);
	free(nf);
	free_strings(keys);
	free_strings(boot);
	return ret;
}

JNIEXPORT jint JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeDhtStop(JNIEnv *env, jobject thiz)
{
	return unetd_core_dht_stop();
}

JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeStatus(JNIEnv *env, jobject thiz)
{
	char *json = unetd_core_status_json();
	jstring ret;

	if (!json)
		return NULL;
	ret = (*env)->NewStringUTF(env, json);
	free(json);
	return ret;
}

/* A line (or several) from Kotlin straight into the log ring. */
JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeLog(JNIEnv *env, jobject thiz, jstring line)
{
	const char *s = line ? (*env)->GetStringUTFChars(env, line, NULL) : NULL;

	if (!s)
		return;
	unetd_log_push(s);
	(*env)->ReleaseStringUTFChars(env, line, s);
}

/*
 * The lines since a sequence number: the first line of the result is the
 * sequence number to ask for next time, the rest are the log lines.
 */
JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeLogSince(JNIEnv *env, jobject thiz, jlong since, jint max_lines)
{
	uint64_t next = 0;
	char *lines = unetd_log_since(since > 0 ? (uint64_t)since : 0, max_lines > 0 ? (size_t)max_lines : 500, &next);
	char head[32];
	char *text;
	jstring ret;

	if (!lines)
		return NULL;
	snprintf(head, sizeof(head), "%llu\n", (unsigned long long)next);
	text = malloc(strlen(head) + strlen(lines) + 1);
	if (!text) {
		free(lines);
		return NULL;
	}
	strcpy(text, head);
	strcat(text, lines);
	free(lines);
	ret = (*env)->NewStringUTF(env, text);
	free(text);
	return ret;
}

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeStartLogCapture(JNIEnv *env, jobject thiz)
{
	unetd_log_capture_start(false);
}

JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeLogTail(JNIEnv *env, jobject thiz, jint max_lines)
{
	char *text = unetd_log_tail(max_lines > 0 ? (size_t)max_lines : 200);
	jstring ret;

	if (!text)
		return NULL;
	ret = (*env)->NewStringUTF(env, text);
	free(text);
	return ret;
}

JNIEXPORT jobjectArray JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeGenerateKey(JNIEnv *env, jobject thiz)
{
	char priv[UNETD_KEY_B64_LEN], pub[UNETD_KEY_B64_LEN];
	jobjectArray arr;
	jclass string_class;

	if (unetd_core_generate_key(priv, sizeof(priv), pub, sizeof(pub)) < 0)
		return NULL;

	string_class = (*env)->FindClass(env, "java/lang/String");
	arr = (*env)->NewObjectArray(env, 2, string_class, NULL);
	if (!arr)
		return NULL;
	(*env)->SetObjectArrayElement(env, arr, 0, (*env)->NewStringUTF(env, priv));
	(*env)->SetObjectArrayElement(env, arr, 1, (*env)->NewStringUTF(env, pub));
	memset(priv, 0, sizeof(priv));
	return arr;
}

JNIEXPORT jstring JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativePublicKey(JNIEnv *env, jobject thiz, jstring priv)
{
	char pub[UNETD_KEY_B64_LEN];
	char *p = dup_jstring(env, priv);
	int ret = unetd_core_public_key(p, pub, sizeof(pub));

	free(p);
	if (ret < 0)
		return NULL;
	return (*env)->NewStringUTF(env, pub);
}
