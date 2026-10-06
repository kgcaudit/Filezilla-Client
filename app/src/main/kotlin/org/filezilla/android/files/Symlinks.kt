package org.filezilla.android.files

import java.io.File

/**
 * Whether [file] is a symbolic link.
 *
 * The one judgement three file routines -- the copy/delete operations, the
 * recursive walk, and the listing -- each used to make for themselves off
 * `canonicalPath != absolutePath`, written out once so the three cannot
 * drift apart. Links are not followed: deleting or copying through one would
 * reach outside the folder the user is looking at, which is not what they
 * asked for and not something they could see coming.
 *
 * Best-effort: resolving the canonical path touches the filesystem and can
 * throw -- a path no longer there, a wall the app cannot read past -- and a
 * file whose linkness cannot be told is treated as not a link. That is the
 * conservative answer here, because the callers use this to decide whether to
 * follow something, and the safe choice when unsure is to treat it as an
 * ordinary file rather than to chase a link out of the tree.
 */
internal fun isLink(file: File): Boolean =
    runCatching { file.canonicalPath != file.absolutePath }.getOrDefault(false)
