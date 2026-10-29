package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.storage.service.StorageManager

@Inject
@SingleIn(AppScope::class)
class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    fun getFilesInBaseDirectory(): List<UniFile> {
        val localFiles = getBaseDirectory()?.listFiles().orEmpty().toList()
        val finishedFiles = storageManager.getFinishedSourceDirectory()?.listFiles().orEmpty().toList()
        return (localFiles + finishedFiles).distinctBy { it.name?.lowercase() }
    }

    fun getMangaDirectory(name: String): UniFile? {
        val localDir = getBaseDirectory()?.findFile(name)?.takeIf { it.isDirectory }
            ?: getBaseDirectory()?.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name?.equals(name, ignoreCase = true) == true }
        if (localDir != null) return localDir

        return storageManager.getFinishedSourceDirectory()?.findFile(name)?.takeIf { it.isDirectory }
            ?: storageManager.getFinishedSourceDirectory()?.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name?.equals(name, ignoreCase = true) == true }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
