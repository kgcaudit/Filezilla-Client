package org.filezilla.android.ui

import androidx.annotation.DrawableRes
import org.filezilla.android.R

/**
 * What a row is, as far as an icon is concerned.
 *
 * Coarse on purpose. The point of colouring a row's tile is that a list can
 * be scanned rather than read, and a scan can tell six or seven things apart,
 * not thirty. So "a picture" is one kind whether it is a jpg or a heic, and
 * anything unrecognised is [OTHER] rather than being guessed at.
 */
enum class FileKind(@DrawableRes val glyph: Int) {
    FOLDER(R.drawable.ic_tile_folder),
    ARCHIVE(R.drawable.ic_tile_archive),
    IMAGE(R.drawable.ic_tile_image),
    VIDEO(R.drawable.ic_tile_video),
    AUDIO(R.drawable.ic_tile_audio),
    DOCUMENT(R.drawable.ic_tile_document),
    CODE(R.drawable.ic_tile_code),
    APP(R.drawable.ic_tile_app),
    OTHER(R.drawable.ic_tile_document),
}

/**
 * The kind of a row, from its name.
 *
 * The extension and nothing else: a listing gives a name and a flag, and a
 * server is under no obligation to give a type. A name with no extension, or
 * one this table has not seen, is [FileKind.OTHER] -- which is honest, and
 * the commonest answer on a server full of release names.
 */
fun kindOf(name: String, isDirectory: Boolean): FileKind {
    if (isDirectory) return FileKind.FOLDER
    val cut = name.lastIndexOf('.')
    if (cut <= 0) return FileKind.OTHER
    return BY_EXTENSION[name.substring(cut + 1).lowercase()] ?: FileKind.OTHER
}

private val BY_EXTENSION: Map<String, FileKind> = buildMap {
    // alz and egg are ESTsoft's, they are everywhere in Korea, and this
    // app opens both -- so a row that is one should look like an archive
    // rather than like a file nothing knows about.
    for (e in "zip rar 7z tar gz bz2 xz tgz iso alz egg a00 a01".split(" ")) put(e, FileKind.ARCHIVE)
    for (e in "jpg jpeg png gif webp bmp heic heif tiff tif svg".split(" ")) put(e, FileKind.IMAGE)
    for (e in "mkv mp4 avi mov wmv flv webm m4v mpg mpeg ts m2ts".split(" ")) put(e, FileKind.VIDEO)
    for (e in "mp3 flac wav aac ogg m4a wma opus".split(" ")) put(e, FileKind.AUDIO)
    // Subtitles count as documents: they are text, and on a server full of
    // films they sit beside the video they belong to and should not look
    // like one.
    for (e in "pdf doc docx xls xlsx ppt pptx txt md rtf odt hwp srt smi ass vtt sub"
        .split(" ")) {
        put(e, FileKind.DOCUMENT)
    }
    for (e in "kt java py js ts json xml yml yaml html css sh c cpp h rs go rb php"
        .split(" ")) {
        put(e, FileKind.CODE)
    }
    for (e in "apk aab apks xapk".split(" ")) put(e, FileKind.APP)
}
