package org.filezilla.android.ui

import android.os.Build
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
    COMIC(R.drawable.ic_tile_comic),
    IMAGE(R.drawable.ic_tile_image),
    VIDEO(R.drawable.ic_tile_video),
    AUDIO(R.drawable.ic_tile_audio),
    DOCUMENT(R.drawable.ic_tile_document),
    // An e-book (epub) rides the document hue but wears an open-book glyph, the
    // same move COMIC makes on the archive hue -- so a shelf of books reads
    // apart from plain documents without spending a tenth colour on it.
    EBOOK(R.drawable.ic_tile_book),
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
    val extension = name.substring(cut + 1).lowercase()
    // AVIF decodes only on Android 12+, so below that it is not a viewable
    // image and must not wear the image tile -- the viewer would bounce it to
    // another app, the same mismatch tiff was carrying before.
    if (extension == "avif" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return FileKind.OTHER
    return BY_EXTENSION[extension] ?: FileKind.OTHER
}

/** Whether the app's own player shows this file: a video or a sound. */
fun looksMedia(name: String): Boolean = kindOf(name, false).let { it == FileKind.VIDEO || it == FileKind.AUDIO }

/**
 * A PDF, which the app shows in its own page-by-page reader.
 *
 * The extension alone, like every other kind here. A PDF is one of the
 * DOCUMENT extensions, so this is a narrower question than [kindOf]: the tile
 * still says "document", but a tap opens the reader rather than another app.
 */
fun looksPdf(name: String): Boolean =
    name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

/** An EPUB, which the app shows in its own chapter-by-chapter reader. */
fun looksEpub(name: String): Boolean =
    name.substringAfterLast('.', "").equals("epub", ignoreCase = true)

/** A video, so the player shows a picture rather than only controls. */
fun looksVideo(name: String): Boolean = kindOf(name, false) == FileKind.VIDEO

/**
 * Whether a document handed in from another app should stream in the player
 * rather than be copied to a file first.
 *
 * The player reads a URI, so a sound or a film can play straight from the one
 * the other app grants -- no waiting on a copy, which for a large video off a
 * cloud app is the whole file. The kind is taken from the declared type first,
 * since a streaming provider often names its document without an extension, and
 * from the name when there is no type. Everything else (a picture, a PDF, a zip)
 * still becomes a file, because those viewers read one.
 */
fun streamsAsMedia(name: String?, mimeType: String?): Boolean {
    if (mimeType != null && (mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) return true
    return name != null && looksMedia(name)
}

private val BY_EXTENSION: Map<String, FileKind> = buildMap {
    // alz and egg are ESTsoft's, they are everywhere in Korea, and this
    // app opens both -- so a row that is one should look like an archive
    // rather than like a file nothing knows about.
    for (e in "zip rar 7z tar gz bz2 xz tgz iso alz egg a00 a01".split(" ")) put(e, FileKind.ARCHIVE)
    // Comic archives -- a zip, rar, 7z or tar renamed to open in a reader.
    // They are archives, but common enough on a phone full of manga to earn
    // their own open-book tile so a shelf of them is told from a shelf of zips.
    for (e in "cbz cbr cb7 cbt".split(" ")) put(e, FileKind.COMIC)
    // The image tile marks exactly what the in-app viewer can open (see
    // ImageFiles.looksImage), so a picture-coloured row always opens as a
    // picture. svg is in -- it rasterises through the vector path; tiff/tif
    // are out, since nothing on Android decodes them reliably and a tile that
    // promised a viewer that then bounced to "open with" was the confusing bit.
    // wbmp and avif are what BitmapFactory also decodes; avif is gated to
    // Android 12+ in kindOf, where a tap would otherwise bounce to another app.
    for (e in "jpg jpeg png gif webp bmp heic heif wbmp avif svg svgz".split(" ")) put(e, FileKind.IMAGE)
    for (e in "mkv mp4 avi mov wmv flv webm m4v mpg mpeg ts m2ts".split(" ")) put(e, FileKind.VIDEO)
    for (e in "mp3 flac wav aac ogg m4a wma opus".split(" ")) put(e, FileKind.AUDIO)
    // An e-book earns the open-book tile so a shelf of them reads apart from
    // plain documents. pdf and txt stay DOCUMENT across the family; only epub
    // is split off here.
    put("epub", FileKind.EBOOK)
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
