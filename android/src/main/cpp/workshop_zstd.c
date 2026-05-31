#include <jni.h>
#include <stdint.h>
#include <zstd.h>

JNIEXPORT jint JNICALL
Java_top_apricityx_workshop_workshop_AndroidZstdBridge_decompressNative(
        JNIEnv *env,
        jclass clazz,
        jbyteArray destination,
        jbyteArray compressed) {
    (void) clazz;

    if (destination == NULL || compressed == NULL) {
        return -1;
    }

    jsize destination_size = (*env)->GetArrayLength(env, destination);
    jsize compressed_size = (*env)->GetArrayLength(env, compressed);
    if (destination_size < 0 || compressed_size < 0) {
        return -1;
    }

    jbyte *destination_bytes = (*env)->GetByteArrayElements(env, destination, NULL);
    if (destination_bytes == NULL) {
        return -1;
    }

    jbyte *compressed_bytes = (*env)->GetByteArrayElements(env, compressed, NULL);
    if (compressed_bytes == NULL) {
        (*env)->ReleaseByteArrayElements(env, destination, destination_bytes, JNI_ABORT);
        return -1;
    }

    size_t written = ZSTD_decompress(
            destination_bytes,
            (size_t) destination_size,
            compressed_bytes,
            (size_t) compressed_size);

    (*env)->ReleaseByteArrayElements(env, compressed, compressed_bytes, JNI_ABORT);
    if (ZSTD_isError(written) || written > (size_t) INT32_MAX) {
        (*env)->ReleaseByteArrayElements(env, destination, destination_bytes, JNI_ABORT);
        return -1;
    }

    (*env)->ReleaseByteArrayElements(env, destination, destination_bytes, 0);
    return (jint) written;
}
