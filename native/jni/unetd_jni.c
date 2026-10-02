/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * JNI binding for org.unetd.android.nativebridge.Unetd (a Kotlin object).
 *
 * Thin by design: every call goes straight to unetd_core.h, and the two
 * upcalls -- protectSocket() and onNetworkUpdate() -- come from the uloop
 * thread, which is attached to the JVM once for its whole lifetime.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "unetd_core.h"
#include "unetd_log.h"

static JavaVM *jvm;
static jobject callbacks;		/* global ref to an Unetd.Callbacks */
static jmethodID m_protect;		/* boolean protectSocket(int) */
static jmethodID m_update;		/* void onNetworkUpdate(String) */
static JNIEnv *uloop_env;		/* valid between thread_started/stopping */

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
	if (uloop_env)
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
	(*jvm)->AttachCurrentThread(jvm, &uloop_env, NULL);
}

static void cb_thread_stopping(void *priv)
{
	uloop_env = NULL;
	(*jvm)->DetachCurrentThread(jvm);
}

static void cb_protect_socket(void *priv, int fd)
{
	int attached;
	JNIEnv *env = current_env(&attached);

	if (!env || !callbacks)
		return;
	if (!(*env)->CallBooleanMethod(env, callbacks, m_protect, (jint)fd))
		fprintf(stderr, "unetd: VpnService.protect(%d) refused\n", fd);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionClear(env);
		fprintf(stderr, "unetd: VpnService.protect(%d) threw\n", fd);
	}
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

	if (callbacks) {
		(*env)->DeleteGlobalRef(env, callbacks);
		callbacks = NULL;
	}
	if (cb) {
		jclass cls = (*env)->GetObjectClass(env, cb);

		m_protect = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
		m_update = (*env)->GetMethodID(env, cls, "onNetworkUpdate", "(Ljava/lang/String;)V");
		if (!m_protect || !m_update) {
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

JNIEXPORT void JNICALL
Java_org_unetd_android_nativebridge_Unetd_nativeStop(JNIEnv *env, jobject thiz)
{
	unetd_core_stop();
	if (callbacks) {
		(*env)->DeleteGlobalRef(env, callbacks);
		callbacks = NULL;
	}
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
