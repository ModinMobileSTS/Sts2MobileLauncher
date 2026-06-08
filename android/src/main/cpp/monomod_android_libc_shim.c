#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <unistd.h>

#if defined(__GNUC__) || defined(__clang__)
#define STS2_EXPORT __attribute__((visibility("default")))
#else
#define STS2_EXPORT
#endif

STS2_EXPORT void monomod_android_clear_cache(void *start, void *end) {
#if defined(__GNUC__) || defined(__clang__)
    __builtin___clear_cache((char *)start, (char *)end);
#else
    (void)start;
    (void)end;
#endif
}

STS2_EXPORT int monomod_android_patch_code(void *target, const void *data, size_t length) {
    if (target == NULL || data == NULL) {
        errno = EINVAL;
        return -1;
    }

    int fd = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        return -1;
    }

    const uint8_t *source = (const uint8_t *)data;
    uintptr_t address = (uintptr_t)target;
    size_t written = 0;
    while (written < length) {
        ssize_t result = pwrite(fd, source + written, length - written, (off_t)(address + written));
        if (result < 0) {
            int saved_errno = errno;
            close(fd);
            errno = saved_errno;
            return -1;
        }
        if (result == 0) {
            close(fd);
            errno = EIO;
            return -1;
        }
        written += (size_t)result;
    }

    close(fd);
    monomod_android_clear_cache(target, (void *)(address + length));
    return 0;
}
