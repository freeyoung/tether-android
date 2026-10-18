// The JNI layer of io.github.freeyoung.tether.Native over export_android.go.
//
// All data crosses as UTF-8 bytes in byte arrays, not as jstring: JNI's
// "modified UTF-8" breaks text with emoji.

#include <android/log.h>
#include <jni.h>
#include <stdlib.h>

#include "_cgo_export.h"

#define FN(name) Java_io_github_freeyoung_tether_Native_##name

void tetherLog(const char* message) { __android_log_write(ANDROID_LOG_INFO, "TetherCore", message); }

// Copies a Java byte array to C memory. The caller frees it.
static char* bytes_in(JNIEnv* env, jbyteArray array, int* len) {
    *len = 0;
    if (array == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    char* buf = malloc(n > 0 ? n : 1);
    if (buf == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, n, (jbyte*)buf);
    *len = n;
    return buf;
}

// Makes a Java byte array of data, and frees data.
static jbyteArray bytes_out(JNIEnv* env, char* data, int len) {
    if (data == NULL) return NULL;
    jbyteArray array = (*env)->NewByteArray(env, len);
    if (array != NULL) (*env)->SetByteArrayRegion(env, array, 0, len, (const jbyte*)data);
    free(data);
    return array;
}

JNIEXPORT jbyteArray JNICALL FN(start)(JNIEnv* env, jclass cls, jbyteArray config) {
    int len;
    char* in = bytes_in(env, config, &len);
    int n = 0;
    char* result = tetherStart(in, len, &n);
    free(in);
    return bytes_out(env, result, n);
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv* env, jclass cls, jlong handle) { tetherStop(handle); }

JNIEXPORT jbyteArray JNICALL FN(nextEvent)(JNIEnv* env, jclass cls, jlong handle) {
    int n = 0;
    char* event = tetherNextEvent(handle, &n);
    return bytes_out(env, event, n);
}

JNIEXPORT jbyteArray JNICALL FN(call)(JNIEnv* env, jclass cls, jlong handle, jbyteArray op, jbyteArray arg) {
    int op_len, arg_len;
    char* op_in = bytes_in(env, op, &op_len);
    char* arg_in = bytes_in(env, arg, &arg_len);
    int n = 0;
    char* result = tetherCall(handle, op_in, op_len, arg_in, arg_len, &n);
    free(op_in);
    free(arg_in);
    return bytes_out(env, result, n);
}
