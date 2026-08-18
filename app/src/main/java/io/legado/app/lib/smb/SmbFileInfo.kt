package io.legado.app.lib.smb

/**
 * smbFile
 */
@Suppress("unused")
class SmbFileInfo(
    val url: String,
    val displayName: String,
    val size: Long,
    val lastModify: Long,
    val isDir: Boolean
)
