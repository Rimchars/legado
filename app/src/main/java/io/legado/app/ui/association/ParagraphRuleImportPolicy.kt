package io.legado.app.ui.association

import io.legado.app.data.entities.ParagraphRule
import java.io.File
import java.io.IOException

object ParagraphRuleImportPolicy {
    const val MAX_EDITABLE_BYTES = 4L * 1024L * 1024L
    const val MAX_PACKAGE_BYTES = 16L * 1024L * 1024L

    fun requirePackageFile(file: File) {
        if (!file.isFile || file.length() !in 1..MAX_PACKAGE_BYTES) {
            throw IOException("Paragraph rule package exceeds the 16 MiB safety limit")
        }
    }

    fun requirePackageText(raw: String) {
        if (raw.isEmpty() || utf8ByteCount(raw, MAX_PACKAGE_BYTES) > MAX_PACKAGE_BYTES) {
            throw IOException("Paragraph rule package exceeds the 16 MiB safety limit")
        }
    }

    fun isEditable(rule: ParagraphRule): Boolean {
        var total = 0L
        for (value in listOf(rule.name, rule.script, rule.jsLib, rule.loginUi, rule.loginUrl)) {
            total += utf8ByteCount(value, MAX_EDITABLE_BYTES - total)
            if (total > MAX_EDITABLE_BYTES) return false
        }
        return true
    }

    internal fun utf8ByteCount(value: String, stopAfter: Long = Long.MAX_VALUE): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }
                else -> 3
            }
            if (bytes > stopAfter) return bytes
            index++
        }
        return bytes
    }
}
