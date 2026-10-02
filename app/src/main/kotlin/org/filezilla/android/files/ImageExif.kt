package org.filezilla.android.files

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * What a photo's EXIF says, read into one value the info sheet renders.
 *
 * Every field is nullable because a server, a screenshot or a scrubbed photo
 * may carry none of it -- a row with nothing to show is dropped, not faked.
 * Kept apart from [androidx.exifinterface.media.ExifInterface] so the reading
 * rule is stated once and tested without a file or a device.
 */
data class ExifFacts(
    /** When it was taken, epoch millis in the tag's own (local) clock, or null. */
    val dateTaken: Long? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    /** Aperture, as the number after the f: f/[fNumber]. */
    val fNumber: Double? = null,
    /** Shutter, in seconds. */
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    /** Focal length in mm, as the lens reports it. */
    val focalLength: Double? = null,
    /** Focal length in 35mm-equivalent mm, when the camera gives it. */
    val focalLength35: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** Whether there is anything at all to show, so an empty sheet can say so. */
    val hasAny: Boolean
        get() = dateTaken != null || cameraMake != null || cameraModel != null || lensModel != null ||
            fNumber != null || exposureSeconds != null || iso != null || focalLength != null ||
            (width != null && height != null) || hasLocation
}

/**
 * Reads and edits image EXIF, through androidx's ExifInterface.
 *
 * Reading covers the formats that carry EXIF (JPEG, PNG, WebP, HEIF, and the
 * raw formats); writing is only for the three the library can save back into
 * without re-encoding the pixels -- JPEG, PNG and WebP -- so [canEdit] gates the
 * edit actions and the sheet hides them otherwise.
 *
 * The three edits are all lossless: a rotate only rewrites the orientation tag,
 * and the two strips only clear tags -- none of them touches the image data, so
 * a photo never loses quality to having its metadata read or cleaned.
 */
object ImageExif {

    /** The formats androidx ExifInterface can save attributes back into. */
    private val WRITABLE = setOf("jpg", "jpeg", "png", "webp")

    /** Whether [name]'s extension is one whose EXIF can be written back. */
    fun canEdit(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in WRITABLE

    // --------------------------------------------------------------- reading

    fun read(file: File): ExifFacts = runCatching { read(ExifInterface(file.absolutePath)) }.getOrDefault(ExifFacts())

    fun read(stream: InputStream): ExifFacts = runCatching { read(ExifInterface(stream)) }.getOrDefault(ExifFacts())

    /** The pure read: pulls each field from an already-opened [exif]. */
    fun read(exif: ExifInterface): ExifFacts {
        val latLon = exif.latLong // [lat, lon], or null
        return ExifFacts(
            dateTaken = parseDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)),
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.ifBlank { null },
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.ifBlank { null },
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifBlank { null },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
            exposureSeconds = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 },
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
            focalLength35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
            width = dimension(exif, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_PIXEL_X_DIMENSION),
            height = dimension(exif, ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_PIXEL_Y_DIMENSION),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
            latitude = latLon?.getOrNull(0),
            longitude = latLon?.getOrNull(1),
        )
    }

    private fun dimension(exif: ExifInterface, primary: String, fallback: String): Int? {
        val a = exif.getAttributeInt(primary, 0)
        if (a > 0) return a
        val b = exif.getAttributeInt(fallback, 0)
        return b.takeIf { it > 0 }
    }

    /** Parses EXIF's "yyyy:MM:dd HH:mm:ss" into epoch millis, or null. */
    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            // The tag has no zone; read it as-is rather than shifting the clock
            // the photo was taken on into the phone's current one.
            val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            fmt.parse(raw.trim())?.time
        }.getOrNull()
    }

    // --------------------------------------------------------------- editing

    /**
     * Turns the photo a quarter-turn without touching a pixel: it rewrites the
     * orientation tag so every viewer that honours it shows the new way up.
     *
     * [clockwise] true turns right, false left. The new tag is worked out from
     * the old one through [rotatedOrientation], so a photo that already carried
     * a rotation or a mirror turns correctly rather than being reset to upright.
     */
    fun rotate(file: File, clockwise: Boolean): Boolean = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val now = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, rotatedOrientation(now, clockwise).toString())
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    /** Clears just the location tags, leaving everything else as it was. */
    fun clearLocation(file: File): Boolean = runCatching {
        val exif = ExifInterface(file.absolutePath)
        clearTags(exif, GPS_TAGS)
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    /**
     * Clears every identifying tag -- location, the camera and lens, the
     * timestamps, the capture settings, the software and any comment -- so the
     * photo can be shared with nothing of where, when or on what it was taken.
     *
     * Orientation is kept on purpose: dropping it would leave the photo on its
     * side in viewers that had been relying on the tag to stand it up.
     */
    fun clearAll(file: File): Boolean = runCatching {
        val exif = ExifInterface(file.absolutePath)
        clearTags(exif, GPS_TAGS + IDENTIFYING_TAGS)
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    private fun clearTags(exif: ExifInterface, tags: List<String>) {
        for (tag in tags) exif.setAttribute(tag, null)
    }

    /**
     * The orientation tag after a quarter-turn of the shown image.
     *
     * Each EXIF orientation is split into (rotation, mirrored); the turn adds a
     * quarter to the rotation and keeps the mirror, then it is mapped back. So a
     * mirrored or already-rotated photo turns correctly, not just an upright one.
     */
    fun rotatedOrientation(orientation: Int, clockwise: Boolean): Int {
        val (rot, mirror) = TO_TRANSFORM[orientation] ?: (0 to false)
        val turned = ((rot + if (clockwise) 90 else 270) % 360)
        return FROM_TRANSFORM[turned to mirror] ?: ExifInterface.ORIENTATION_NORMAL
    }

    // EXIF orientation <-> (clockwise rotation to display, mirrored horizontally).
    private val TO_TRANSFORM: Map<Int, Pair<Int, Boolean>> = mapOf(
        ExifInterface.ORIENTATION_NORMAL to (0 to false),
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (0 to true),
        ExifInterface.ORIENTATION_ROTATE_180 to (180 to false),
        ExifInterface.ORIENTATION_FLIP_VERTICAL to (180 to true),
        ExifInterface.ORIENTATION_TRANSPOSE to (270 to true),
        ExifInterface.ORIENTATION_ROTATE_90 to (90 to false),
        ExifInterface.ORIENTATION_TRANSVERSE to (90 to true),
        ExifInterface.ORIENTATION_ROTATE_270 to (270 to false),
    )
    private val FROM_TRANSFORM: Map<Pair<Int, Boolean>, Int> =
        TO_TRANSFORM.entries.associate { (orientation, transform) -> transform to orientation }

    /** The GPS tags, cleared by both strips. */
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
    )

    /** The who/when/where-taken tags, cleared by the full strip (not orientation). */
    private val IDENTIFYING_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    )
}
