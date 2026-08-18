package io.legado.app.constant

import java.util.regex.Pattern

@Suppress("RegExpRedundantEscape", "unused")
object AppPattern {
    val JS_PATTERN: Pattern =
        Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)
    val WebJS_PATTERN: Pattern =
        Pattern.compile("@webjs:([\\w\\W]{5,})", Pattern.CASE_INSENSITIVE)
    val EXP_PATTERN: Pattern = Pattern.compile("\\{\\{([\\w\\W]*?)\\}\\}")

    //匹配格式化后的图片格式
    val imgPattern: Pattern = Pattern.compile("<img[^>]*src=\"([^\"]*(?:\"[^>]+\\})?)\"[^>]*>")

    fun isVirtualImageSrc(src: String?): Boolean {
        val value = src?.trim() ?: return false
        return value.startsWith("dp:", ignoreCase = true) ||
                value.startsWith("bubble://paragraph", ignoreCase = true)
    }

    //匹配自定义html格式字符串
    val useHtmlRegex = Regex("<usehtml>.*?</usehtml>", RegexOption.DOT_MATCHES_ALL) //.包含换行

    //dataURL图片类型
    val dataUriRegex = Regex("^data:.*?;base64,(.*)")
    //提取标题中的段评
    val imgRegex = Regex("(.*)((?:data|https?):[\\s\\S]+)$", RegexOption.IGNORE_CASE)

    //压缩包漫画图片文件名
    val imageFileRegex = Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp|avif|heic|heif)$", RegexOption.IGNORE_CASE)

    fun splitTitleImage(title: String): Pair<String, String>? {
        val paragraphBubbleStart = findTitleParagraphBubbleStart(title)
        val imageStart = findTitleImageStart(title)
        if (paragraphBubbleStart >= 0 && (imageStart < 0 || paragraphBubbleStart < imageStart)) {
            return title.substring(0, paragraphBubbleStart) to title.substring(paragraphBubbleStart)
        }
        if (imageStart < 0) return null
        return title.substring(0, imageStart) to title.substring(imageStart)
    }

    private fun findTitleParagraphBubbleStart(title: String): Int {
        var start = title.indexOf("dp:", ignoreCase = true)
        while (start >= 0) {
            var payloadStart = start + 3
            while (payloadStart < title.length && title[payloadStart].isWhitespace()) {
                payloadStart++
            }
            if (payloadStart < title.length && title[payloadStart].isDigit()) {
                return start
            }
            start = title.indexOf("dp:", start + 3, ignoreCase = true)
        }
        return -1
    }

    private fun findTitleImageStart(title: String): Int {
        val dataStart = title.lastIndexOf("data:", ignoreCase = true)
            .takeIf { it >= 0 && title.length > it + 5 } ?: -1
        val httpStart = title.lastIndexOf("http:", ignoreCase = true)
            .takeIf { it >= 0 && title.length > it + 5 } ?: -1
        val httpsStart = title.lastIndexOf("https:", ignoreCase = true)
            .takeIf { it >= 0 && title.length > it + 6 } ?: -1
        return maxOf(dataStart, httpStart, httpsStart)
    }
    //匹配章节信息中的字数
    val wordCountRegex = Regex("(?:^|字数[：:、]?|\\s+)([0-9万千百\\.]{1,6}字)")

    //正文不计入字数的字符
    val noWordCountRegex = Regex("[\\s\\u200B-\\u200F\\uFEFF]")

    //提取链接中的域名
    val domainRegex = Regex("^https?://([^:/]+)",RegexOption.IGNORE_CASE)

    val nameRegex = Regex("\\s+作\\s*者.*|\\s+\\S+\\s+著")
    val authorRegex = Regex("^\\s*作\\s*者[:：\\s]+|\\s+著")
    val fileNameRegex = Regex("[\\\\/:*?\"<>|.]")
    val fileNameRegex2 = Regex("[\\\\/:*?\"<>|]")
    val splitGroupRegex = Regex("[,;，；]")
    val titleNumPattern: Pattern = Pattern.compile("(第)(.+?)(章)")

    //书源调试信息中的各种符号
    val debugMessageSymbolRegex = Regex("[⇒◇┌└≡]")

    //本地书籍支持类型
    val bookFileRegex = Regex(".*\\.(txt|epub|umd|pdf|mobi|azw3|azw)", RegexOption.IGNORE_CASE)
    //压缩文件支持类型
    val archiveFileRegex = Regex(".*\\.(zip|rar|7z)$", RegexOption.IGNORE_CASE)

    /**
     * 所有标点
     */
    val bdRegex = Regex("(\\p{P})+")

    /**
     * 换行
     */
    val rnRegex = Regex("[\\r\\n]")

    /**
     * 不发音段落判断
     */
    val notReadAloudRegex = Regex("^(\\s|\\p{C}|\\p{P}|\\p{Z}|\\p{S})+$")

    val xmlContentTypeRegex = "(application|text)/\\w*\\+?xml.*".toRegex()

    val semicolonRegex = ";".toRegex()

    val equalsRegex = "=".toRegex()

    val spaceRegex = "\\s+".toRegex()

    val regexCharRegex = "[{}()\\[\\].+*?^$\\\\|]".toRegex()

    val LFRegex = "\n".toRegex()
}
