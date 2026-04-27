package io.legado.app.help.ai

import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AiLibraryTool {

    private const val TOOL_QUERY_READ_RECORDS = "query_read_records"
    private const val TOOL_SEARCH_BOOK_SOURCE = "search_book_source"
    private const val DEFAULT_LIMIT = 8
    private const val MAX_LIMIT = 20
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun resolvedTools(): List<AiResolvedTool> {
        return listOf(
            AiResolvedTool(
                name = TOOL_QUERY_READ_RECORDS,
                definition = queryReadRecordsDefinition(),
                execute = { args -> queryReadRecords(args) }
            ),
            AiResolvedTool(
                name = TOOL_SEARCH_BOOK_SOURCE,
                definition = searchBookSourceDefinition(),
                execute = { args -> searchBookSource(args) }
            )
        )
    }

    private fun queryReadRecordsDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_QUERY_READ_RECORDS)
                put("description", "查询本地阅读时长、每日阅读记录和书籍阅读排行。可按书名或日期范围筛选。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("bookName", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，按书名模糊筛选阅读时长排行。")
                        })
                        put("startDate", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，开始日期，格式 yyyy-MM-dd，用于每日阅读记录。")
                        })
                        put("endDate", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，结束日期，格式 yyyy-MM-dd，用于每日阅读记录。")
                        })
                        put("limit", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", MAX_LIMIT)
                        })
                    })
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun searchBookSourceDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_SEARCH_BOOK_SOURCE)
                put("description", "调用本地书源搜索书籍，返回可用于打开详情页或视频页的真实搜索结果。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("keyword", JSONObject().apply {
                            put("type", "string")
                            put("description", "搜索关键词，通常是书名、作者或影视名。")
                        })
                        put("sourceUrl", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，指定单个书源 URL。")
                        })
                        put("sourceGroup", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，指定书源分组。sourceUrl 优先。")
                        })
                        put("limit", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", MAX_LIMIT)
                        })
                    })
                    put("required", JSONArray(listOf("keyword")))
                    put("additionalProperties", false)
                })
            })
        }
    }

    private suspend fun queryReadRecords(arguments: JSONObject?): String = withContext(IO) {
        val bookName = arguments?.optString("bookName")?.trim().orEmpty()
        val startDate = arguments?.optString("startDate")?.trim().orEmpty()
        val endDate = arguments?.optString("endDate")?.trim().orEmpty()
        val limit = (arguments?.optInt("limit", DEFAULT_LIMIT) ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)
        val bookRecords = if (bookName.isBlank()) {
            appDb.readRecordDao.allShow
        } else {
            appDb.readRecordDao.search(bookName)
        }.sortedByDescending { it.readTime }.take(limit)
        val dailyRecords = appDb.readRecordDailyDao.allDesc.filter { record ->
            (startDate.isBlank() || record.date >= startDate) &&
                    (endDate.isBlank() || record.date <= endDate)
        }.take(limit)
        JSONObject().apply {
            put("ok", true)
            put("totalReadTimeMillis", appDb.readRecordDao.allTime)
            put("totalReadTimeText", formatDuration(appDb.readRecordDao.allTime))
            put("bookRecords", JSONArray().apply {
                bookRecords.forEach { record ->
                    put(JSONObject().apply {
                        put("bookName", record.bookName)
                        put("readTimeMillis", record.readTime)
                        put("readTimeText", formatDuration(record.readTime))
                        put("lastRead", formatTime(record.lastRead))
                    })
                }
            })
            put("dailyRecords", JSONArray().apply {
                dailyRecords.forEach { record ->
                    put(JSONObject().apply {
                        put("date", record.date)
                        put("readTimeMillis", record.readTime)
                        put("readTimeText", formatDuration(record.readTime))
                        put("updatedAt", formatTime(record.updatedAt))
                    })
                }
            })
        }.toString()
    }

    private suspend fun searchBookSource(arguments: JSONObject?): String = withContext(IO) {
        val keyword = arguments?.optString("keyword")?.trim().orEmpty()
        if (keyword.isBlank()) {
            return@withContext errorJson("keyword 不能为空")
        }
        val limit = (arguments?.optInt("limit", DEFAULT_LIMIT) ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)
        val sources = resolveSources(arguments).filter { !it.searchUrl.isNullOrBlank() }
        if (sources.isEmpty()) {
            return@withContext errorJson("未找到可搜索书源")
        }
        val results = arrayListOf<SearchBook>()
        val errors = JSONArray()
        for (source in sources) {
            if (results.size >= limit) break
            runCatching {
                WebBook.searchBookAwait(
                    bookSource = source,
                    key = keyword,
                    page = 1,
                    shouldBreak = { size -> results.size + size >= limit }
                )
            }.onSuccess { books ->
                appDb.searchBookDao.insert(*books.toTypedArray())
                results += books.take(limit - results.size)
            }.onFailure { throwable ->
                errors.put(JSONObject().apply {
                    put("source", source.bookSourceName)
                    put("error", throwable.localizedMessage ?: throwable.javaClass.simpleName)
                })
            }
        }
        JSONObject().apply {
            put("ok", true)
            put("keyword", keyword)
            put("searchedSourceCount", sources.size)
            put("results", JSONArray().apply {
                results.distinctBy { it.bookUrl }.take(limit).forEach { put(searchBookToJson(it)) }
            })
            put("errors", errors)
        }.toString()
    }

    private fun resolveSources(arguments: JSONObject?): List<BookSource> {
        val sourceUrl = arguments?.optString("sourceUrl")?.trim().orEmpty()
        if (sourceUrl.isNotBlank()) {
            return appDb.bookSourceDao.getBookSource(sourceUrl)?.let(::listOf).orEmpty()
        }
        val sourceGroup = arguments?.optString("sourceGroup")?.trim().orEmpty()
        if (sourceGroup.isNotBlank()) {
            return appDb.bookSourceDao.getEnabledByGroup(sourceGroup)
        }
        return appDb.bookSourceDao.allEnabled
    }

    private fun searchBookToJson(book: SearchBook): JSONObject {
        val isVideo = appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceType == BookSourceType.video
        return JSONObject().apply {
            put("name", book.name)
            put("author", book.author)
            put("bookUrl", book.bookUrl)
            put("origin", book.origin)
            put("originName", book.originName)
            put("kind", book.kind ?: "")
            put("coverUrl", book.coverUrl ?: "")
            put("intro", book.intro ?: "")
            put("latestChapterTitle", book.latestChapterTitle ?: "")
            put("target", if (isVideo) "video" else "bookInfo")
            put("actionMarkdown", buildOpenMarkdown(book, isVideo))
            put("openAction", JSONObject().apply {
                put("type", "open_search_book")
                put("target", if (isVideo) "video" else "bookInfo")
                put("name", book.name)
                put("author", book.author)
                put("bookUrl", book.bookUrl)
                put("origin", book.origin)
                put("originName", book.originName)
            })
        }
    }

    private fun buildOpenMarkdown(book: SearchBook, isVideo: Boolean): String {
        val url = buildString {
            append("legado-search-book://open?")
            append("target=").append(if (isVideo) "video" else "bookInfo")
            append("&name=").append(book.name.encodeUrl())
            append("&author=").append(book.author.encodeUrl())
            append("&bookUrl=").append(book.bookUrl.encodeUrl())
            append("&origin=").append(book.origin.encodeUrl())
            append("&originName=").append(book.originName.encodeUrl())
        }
        return "[打开《${book.name.ifBlank { "结果" }}》]($url)"
    }

    private fun String.encodeUrl(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name())
    }

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()
    }

    private fun formatTime(time: Long): String {
        if (time <= 0L) return ""
        return timeFormat.format(Date(time))
    }

    private fun formatDuration(millis: Long): String {
        val minutes = millis / 60000L
        val days = minutes / (60 * 24)
        val hours = minutes % (60 * 24) / 60
        val leftMinutes = minutes % 60
        return buildString {
            if (days > 0) append(days).append("天")
            if (hours > 0) append(hours).append("小时")
            append(leftMinutes).append("分钟")
        }
    }
}
