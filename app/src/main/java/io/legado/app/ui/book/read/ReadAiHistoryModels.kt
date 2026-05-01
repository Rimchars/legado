package io.legado.app.ui.book.read

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class ReadAiBookHistory(
    val bookUrl: String,
    val bookName: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val records: List<ReadAiHistoryRecord> = emptyList()
)

@Keep
data class ReadAiHistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val answer: String,
    val chapterTitle: String = "",
    val chapterIndex: Int = -1,
    val createdAt: Long = System.currentTimeMillis()
)
