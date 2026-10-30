package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
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
        val validName = DiskUtil.buildValidFilename(name)

        val localDir = getBaseDirectory()?.findFile(name)?.takeIf { it.isDirectory }
            ?: getBaseDirectory()?.findFile(validName)?.takeIf { it.isDirectory }
            ?: getBaseDirectory()?.listFiles().orEmpty().firstOrNull {
                it.isDirectory && (
                    it.name?.equals(name, ignoreCase = true) == true ||
                        it.name?.equals(validName, ignoreCase = true) == true ||
                        DiskUtil.buildValidFilename(it.name.orEmpty()).equals(validName, ignoreCase = true)
                    )
            }
        if (localDir != null) return localDir

        val finishedDir = storageManager.getFinishedSourceDirectory()
        return finishedDir?.findFile(name)?.takeIf { it.isDirectory }
            ?: finishedDir?.findFile(validName)?.takeIf { it.isDirectory }
            ?: finishedDir?.listFiles().orEmpty().firstOrNull {
                it.isDirectory && (
                    it.name?.equals(name, ignoreCase = true) == true ||
                        it.name?.equals(validName, ignoreCase = true) == true ||
                        DiskUtil.buildValidFilename(it.name.orEmpty()).equals(validName, ignoreCase = true)
                    )
            }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }
}
