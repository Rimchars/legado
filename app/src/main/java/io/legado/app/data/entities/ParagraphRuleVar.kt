package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "paragraph_rule_vars",
    primaryKeys = ["ruleId", "name"],
    foreignKeys = [ForeignKey(
        entity = ParagraphRule::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["ruleId"])]
)
data class ParagraphRuleVar(
    var ruleId: Long,
    var name: String,
    var value: String
)
