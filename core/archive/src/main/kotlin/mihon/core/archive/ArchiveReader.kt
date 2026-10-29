package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream
import kotlin.concurrent.Volatile

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    private val size = pfd.statSize
    private val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    @Volatile
    private var defaultUseStreamableZip = false

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T {
        if (defaultUseStreamableZip) {
            return useEntriesInternal(useStreamableZip = true, block)
        }
        return try {
            useEntriesInternal(useStreamableZip = false, block)
        } catch (_: ArchiveException) {
            defaultUseStreamableZip = true
            useEntriesInternal(useStreamableZip = true, block)
        }
    }

    private fun <T> useEntriesInternal(useStreamableZip: Boolean, block: (Sequence<ArchiveEntry>) -> T): T {
        return ArchiveInputStream(address, size, useStreamableZip).use {
            block(generateSequence { it.getNextEntry() })
        }
    }

    fun getInputStream(entryName: String): InputStream? {
        if (defaultUseStreamableZip) {
            return getInputStreamInternal(entryName, useStreamableZip = true)
        }
        return try {
            getInputStreamInternal(entryName, useStreamableZip = false)
        } catch (_: ArchiveException) {
            defaultUseStreamableZip = true
            getInputStreamInternal(entryName, useStreamableZip = true)
        }
    }

    private fun getInputStreamInternal(entryName: String, useStreamableZip: Boolean): InputStream? {
        val archive = ArchiveInputStream(address, size, useStreamableZip)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    override fun close() {
        Os.munmap(address, size)
    }
}
