package net.fstab.tachiai.platform.network

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream

internal const val CONNECTION_IMPORT_TIMEOUT_MS = 10_000L

internal fun readConnectionUri(resolver: ContentResolver, uri: Uri, signal: CancellationSignal, deadline: Long): ByteArray {
    fun checkActive() { signal.throwIfCanceled(); check(SystemClock.elapsedRealtime() < deadline) }
    checkActive()
    // CancellationSignal also covers cooperative providers during descriptor acquisition.
    val asset = checkNotNull(resolver.openAssetFileDescriptor(uri, "r", signal))
    asset.use {
        checkActive()
        val length = asset.declaredLength
        if (length > CONNECTION_IMPORT_LIMIT) throw ConnectionImportFailure(ConnectionImportFailure.Category.SIZE)
        val fd = asset.parcelFileDescriptor.fileDescriptor
        val regular = OsConstants.S_ISREG(Os.fstat(fd).st_mode)
        check(asset.startOffset >= 0)
        if (!regular) {
            check(asset.startOffset == 0L)
            if (Build.VERSION.SDK_INT < 30) throw ConnectionImportFailure(ConnectionImportFailure.Category.SOURCE)
            Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        }
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (length < 0 || output.size().toLong() < length) {
            checkActive()
            if (!regular) {
                if (Os.poll(arrayOf(poll), 200) == 0) continue
                check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) == 0)
            }
            val maximum = if (length < 0) buffer.size else minOf(buffer.size.toLong(), length - output.size()).toInt()
            val count = try {
                if (regular) Os.pread(fd, buffer, 0, maximum, asset.startOffset + output.size())
                else Os.read(fd, buffer, 0, maximum)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue else throw error
            }
            if (count == 0) { check(length < 0 || output.size().toLong() == length); break }
            if (output.size() + count > CONNECTION_IMPORT_LIMIT) throw ConnectionImportFailure(ConnectionImportFailure.Category.SIZE)
            output.write(buffer, 0, count)
        }
        checkActive()
        return output.toByteArray()
    }
}
