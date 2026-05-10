package io.legado.app.help.storage

data class SourceRuntimeBackup(
    val version: Int = 1,
    val items: List<SourceRuntimeBackupItem> = emptyList()
)

data class SourceRuntimeBackupItem(
    val sourceType: String = "",
    val sourceKey: String = "",
    val loginInfo: Map<String, String>? = null,
    val sourceVariable: String? = null,
    val sourceValues: Map<String, String> = emptyMap()
)
