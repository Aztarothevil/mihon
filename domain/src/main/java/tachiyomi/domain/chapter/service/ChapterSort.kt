package tachiyomi.domain.chapter.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

fun getChapterSort(
    manga: Manga,
    sortDescending: Boolean = manga.sortDescending(),
): (
    Chapter,
    Chapter,
) -> Int {
    return when (manga.sorting) {
        Manga.CHAPTER_SORTING_SOURCE -> when (sortDescending) {
            true -> { c1, c2 -> compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = true) }
            false -> { c1, c2 -> compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = false) }
        }
        Manga.CHAPTER_SORTING_NUMBER -> when (sortDescending) {
            true -> { c1, c2 -> compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = true) }
            false -> { c1, c2 -> compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = false) }
        }
        Manga.CHAPTER_SORTING_UPLOAD_DATE -> when (sortDescending) {
            true -> { c1, c2 ->
                val res = compareValues(c2.dateUpload, c1.dateUpload)
                if (res != 0) res else compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = true)
            }
            false -> { c1, c2 ->
                val res = compareValues(c1.dateUpload, c2.dateUpload)
                if (res != 0) res else compareChaptersByVolumeAndNumber(manga.title, c1, c2, sortDescending = false)
            }
        }
        Manga.CHAPTER_SORTING_ALPHABET -> when (sortDescending) {
            true -> { c1, c2 -> c2.name.compareToWithCollator(c1.name) }
            false -> { c1, c2 -> c1.name.compareToWithCollator(c2.name) }
        }
        else -> throw NotImplementedError("Invalid chapter sorting method: ${manga.sorting}")
    }
}

fun compareChaptersByVolumeAndNumber(
    mangaTitle: String,
    c1: Chapter,
    c2: Chapter,
    sortDescending: Boolean,
): Int {
    val num1 = if (c1.chapterNumber >= 0) c1.chapterNumber else ChapterRecognition.parseChapterNumber(mangaTitle, c1.name, -1.0)
    val num2 = if (c2.chapterNumber >= 0) c2.chapterNumber else ChapterRecognition.parseChapterNumber(mangaTitle, c2.name, -1.0)

    // 1. If chapter numbers differ, compare chapter numbers
    if (num1 >= 0 && num2 >= 0 && num1 != num2) {
        val vol1 = ChapterRecognition.parseVolumeNumber(mangaTitle, c1.name)
        val vol2 = ChapterRecognition.parseVolumeNumber(mangaTitle, c2.name)

        if (vol1 >= 0 && vol2 >= 0 && vol1 != vol2) {
            return if (sortDescending) vol2.compareTo(vol1) else vol1.compareTo(vol2)
        }

        return if (sortDescending) num2.compareTo(num1) else num1.compareTo(num2)
    }

    // 2. Same chapter number: compare version numbers (.v2, .v3) to keep versions together
    val ver1 = ChapterRecognition.parseVersionNumber(c1.name)
    val ver2 = ChapterRecognition.parseVersionNumber(c2.name)

    if (ver1 != ver2) {
        return if (sortDescending) ver2.compareTo(ver1) else ver1.compareTo(ver2)
    }

    // 3. Compare volumes if specified
    val vol1 = ChapterRecognition.parseVolumeNumber(mangaTitle, c1.name)
    val vol2 = ChapterRecognition.parseVolumeNumber(mangaTitle, c2.name)
    if (vol1 >= 0 && vol2 >= 0 && vol1 != vol2) {
        return if (sortDescending) vol2.compareTo(vol1) else vol1.compareTo(vol2)
    }

    if (c1.sourceOrder != c2.sourceOrder && c1.sourceOrder != 0L && c2.sourceOrder != 0L) {
        return if (sortDescending) c1.sourceOrder.compareTo(c2.sourceOrder) else c2.sourceOrder.compareTo(c1.sourceOrder)
    }

    return if (sortDescending) c2.name.compareToWithCollator(c1.name) else c1.name.compareToWithCollator(c2.name)
}
