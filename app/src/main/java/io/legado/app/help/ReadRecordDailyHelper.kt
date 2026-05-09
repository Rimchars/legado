package io.legado.app.help

import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.receiver.ReadGoalWidgetProvider
import io.legado.app.receiver.ReadRankWidgetProvider
import splitties.init.appCtx
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReadRecordDailyHelper {

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun record(
        readTime: Long,
        timestamp: Long = System.currentTimeMillis(),
        forceWidgetUpdate: Boolean = false
    ) {
        if (readTime <= 0L) return
        val dateKey = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)
        val current = appDb.readRecordDailyDao.get(dateKey)
        val record = if (current == null) {
            ReadRecordDaily(
                date = dateKey,
                readTime = readTime,
                updatedAt = timestamp
            )
        } else {
            current.copy(
                readTime = current.readTime + readTime,
                updatedAt = timestamp
            )
        }
        appDb.readRecordDailyDao.insert(record)
        ReadGoalWidgetProvider.updateAll(appCtx, force = forceWidgetUpdate)
        ReadRankWidgetProvider.updateAll(appCtx, force = forceWidgetUpdate)
    }
}
