package org.filezilla.android.archive

import java.io.File

/**
 * Which archive a file is, and how to open it without caring.
 *
 * Decided on the bytes at the front, not the extension. A `.zip` that is
 * really an EGG opens; a `.txt` somebody renamed opens; and a file called
 * `photos.zip` that is a JPEG is refused rather than half-read. The
 * extension is used for one thing only -- deciding whether a tap is worth
 * looking inside at all -- because opening every tapped file to sniff it
 * would mean a download from the server for every tap.
 */
object Archives {

    enum class Kind(val extensions: List<String>) {
        // The cb* extensions are comic archives: a zip, rar, 7z or tar under
        // a name that opens them in a comic reader. They are the same bytes,
        // so they open through the same readers -- the extension only decides
        // whether a tap is worth looking inside, and their real kind is read
        // from the bytes like everything else.
        ZIP(listOf("zip", "cbz")),
        ALZ(listOf("alz")),
        EGG(listOf("egg")),
        RAR(listOf("rar", "cbr")),
        SEVENZ(listOf("7z", "cb7")),
        TAR(listOf("tar", "cbt")),
    }

    /**
     * What this file is, read from its first bytes, or null if none of them.
     */
    fun kindOf(file: File): Kind? = when {
        ZipArchive.looksLikeZip(file) -> Kind.ZIP
        AlzArchive.looksLikeAlz(file) -> Kind.ALZ
        EggArchive.looksLikeEgg(file) -> Kind.EGG
        RarArchive.looksLikeRar(file) && RarNative.available -> Kind.RAR
        SevenZArchive.looksLikeSevenZ(file) && SevenZipNative.available -> Kind.SEVENZ
        TarArchive.looksLikeTar(file) -> Kind.TAR
        else -> null
    }

    /**
     * Whether a name is worth opening to find out.
     *
     * A guess, and only ever used to decide that. The answer that counts
     * is [kindOf], which has the file in front of it.
     */
    fun looksLikeArchive(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && Kind.entries.any { extension in it.extensions }
    }

    /** Opens [file] as whatever it turns out to be. */
    fun open(file: File): Archive = when (kindOf(file)) {
        Kind.ZIP -> ZipArchive.open(file)
        Kind.ALZ -> AlzArchive.open(file)
        Kind.EGG -> EggArchive.open(file)
        Kind.RAR -> RarArchive.open(file)
        Kind.SEVENZ -> SevenZArchive.open(file)
        Kind.TAR -> TarArchive.open(file)
        null -> throw NotAnArchive("${file.name} is not an archive this app reads")
    }

    /**
     * The part of an archive's name to unpack it beside.
     *
     * `holiday.zip` becomes `holiday`, and a split ALZ's `holiday.a00`
     * becomes `holiday` too rather than `holiday.a00`'s own folder.
     */
    fun folderNameFor(name: String): String {
        val stem = name.substringBeforeLast('.', name)
        return stem.ifBlank { name }
    }
}
