package io.legado.app.data.entities

import android.os.Parcelable
import androidx.media3.common.C
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "paragraph_rules",
    indices = [Index(value = ["id"])]
)
data class ParagraphRule(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L,
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var jsLib: String = "",
    @ColumnInfo(defaultValue = "")
    var loginUrl: String = "",
    @ColumnInfo(defaultValue = "")
    var loginUi: String = "",
    @ColumnInfo(defaultValue = "0")
    var enabledCookieJar: Boolean = false,
    @ColumnInfo(defaultValue = "")
    var script: String = "",
    @ColumnInfo(defaultValue = "3000")
    var timeoutMillisecond: Long = 3000L,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var updateTime: Long = System.currentTimeMillis()
) : Parcelable {

    fun displayName(): String = name.ifBlank { "段落规则" }

    fun validTimeout(): Long = timeoutMillisecond.takeIf { it > 0 } ?: C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
}
