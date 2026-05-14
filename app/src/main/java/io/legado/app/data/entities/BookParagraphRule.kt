package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_paragraph_rules",
    primaryKeys = ["bookUrl", "ruleId"],
    foreignKeys = [ForeignKey(
        entity = ParagraphRule::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["bookUrl"]), Index(value = ["ruleId"])]
)
data class BookParagraphRule(
    var bookUrl: String,
    var ruleId: Long,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    var order: Int = 0
)
