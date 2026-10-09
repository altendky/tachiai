#ifndef TACHIAI_MEMORY_FILE_H
#define TACHIAI_MEMORY_FILE_H
#include <stddef.h>
/* Owned sealed Linux/Android FD, size 1..8192, no plaintext disk fallback.
 * Produces an internal reference for the restricted GnuTLS reader, which
 * borrows this FD via pread without reopening procfs or changing its offset.
 * Keep the FD until OpenConnect has freed its session, then close it. */
int toc_memory_file(const void *data, size_t size, char path[64]);
#endif
