#define _GNU_SOURCE 1
#include "memory_file.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>

int toc_memory_file(const void *data, size_t size, char path[64]) {
    /* bionic's named memfd_create symbol requires API 30. The syscall is
     * available on supported Android kernels independently of that symbol.
     * ENOSYS, sealing or direct-read failure is a hard resource failure. */
    if (!data || !size || size > 8192 || !path) { errno = EINVAL; return -1; }
    int fd = (int)syscall(__NR_memfd_create, "tachiai-route", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) return -1;
    const unsigned char *bytes = data;
    size_t used = 0;
    while (used < size) {
        ssize_t count = write(fd, bytes + used, size - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) goto fail;
        used += (size_t)count;
    }
    if (fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL)) goto fail;
    struct stat info;
    if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_size != (off_t)size) goto fail;
    int seals = fcntl(fd, F_GET_SEALS);
    int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (seals < 0 || (seals & required) != required) goto fail;
    unsigned char probe;
    ssize_t count;
    do { count = pread(fd, &probe, 1, 0); } while (count < 0 && errno == EINTR);
    if (count != 1) goto fail;
    probe = 0;
    int length = snprintf(path, 64, "tachiai-sealed-fd:%d", fd);
    if (length < 0 || length >= 64) goto fail;
    return fd;
fail:
    close(fd);
    return -1;
}
