package com.fsck.k9.message

import android.net.Uri

interface Attachment {
    val state: LoadingState
    var fileName: String?
    val uri: Uri?
    val contentType: String?
    val name: String?
    var size: Long?

    val resizeImageCircumference: Int
    val resizeImageQuality: Int
    var resizeImagesEnabled: Boolean

    enum class LoadingState {
        URI_ONLY,
        METADATA,
        COMPLETE,
        CANCELLED
    }
}
