package com.whumpo.android.ui.photos

/** The exact bytes and name to upload. They are kept for any retry of the same upload ID, never regenerated. */
class PreparedPhoto(val name: String, val jpeg: ByteArray)

/** The photo cannot be used; [message] is safe to show. */
class PhotoRejected(message: String) : Exception(message)

/**
 * Turns a photo the owner picked into the upload contract: at most 10 MiB in, orientation-correct resize within
 * 1600 x 1600, flattened baseline JPEG within 1 MiB, with all metadata (EXIF, GPS, thumbnails) gone.
 * [source] is a platform handle (a content URI on Android).
 */
interface PhotoPreparer {
    @Throws(PhotoRejected::class)
    suspend fun prepare(source: String): PreparedPhoto
}
