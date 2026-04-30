package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking

object BookSourceWebController {

    fun getLoginData(parameters: Map<String, List<String>>): ReturnData {
        val source = getSource(parameters) ?: return ReturnData().setErrorMsg("未找到书源")
        return ReturnData().setData(
            linkedMapOf(
                "url" to source.getKey(),
                "name" to source.getTag(),
                "loginUrl" to source.loginUrl,
                "loginUi" to source.loginUi,
                "loginCheckJs" to source.loginCheckJs(),
                "header" to source.header,
                "loginInfo" to source.getLoginInfoMap(),
                "loginHeader" to source.getLoginHeaderMap(),
            )
        )
    }

    fun saveLoginData(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return returnData.setErrorMsg("数据格式错误")
        val source = getSource(body["type"]?.toString(), body["url"]?.toString())
            ?: return returnData.setErrorMsg("未找到书源")
        val loginInfo = body["loginInfo"]
        if (loginInfo != null) {
            source.putLoginInfo(GSON.toJson(loginInfo))
        }
        kotlin.runCatching {
            source.login()
        }
        return returnData.setData(
            linkedMapOf(
                "url" to source.getKey(),
                "loginInfo" to source.getLoginInfoMap(),
                "loginHeader" to source.getLoginHeaderMap()
            )
        )
    }

    fun getDiscoverSources(): ReturnData {
        val sources = appDb.bookSourceDao.allTextEnabledPart
            .filter { it.enabledExplore && it.hasExploreUrl }
        return ReturnData().setData(sources)
    }

    fun getDiscoverKinds(parameters: Map<String, List<String>>): ReturnData {
        val source = getBookSource(parameters) ?: return ReturnData().setErrorMsg("未找到书源")
        val kinds = runBlocking { source.exploreKinds() }
        return ReturnData().setData(kinds)
    }

    fun getDiscoverBooks(parameters: Map<String, List<String>>): ReturnData {
        val source = getBookSource(parameters) ?: return ReturnData().setErrorMsg("未找到书源")
        val exploreUrl = parameters["exploreUrl"]?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: source.exploreUrl
            ?: return ReturnData().setErrorMsg("发现地址不能为空")
        val page = parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 1
        val books = runBlocking {
            WebBook.exploreBookAwait(source, exploreUrl, page)
        }
        return ReturnData().setData(books)
    }

    fun importBookSource(postData: String?): ReturnData {
        return BookSourceController.saveSource(postData)
    }

    fun importBookSources(postData: String?): ReturnData {
        return BookSourceController.saveSources(postData)
    }

    private fun getSource(parameters: Map<String, List<String>>): BaseSource? {
        val type = parameters["type"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "bookSource"
        val key = parameters["url"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: parameters["key"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        return getSource(type, key)
    }

    private fun getSource(type: String?, key: String?): BaseSource? {
        val sourceKey = key ?: return null
        return when (type) {
            "rssSource" -> appDb.rssSourceDao.getByKey(sourceKey)
            else -> appDb.bookSourceDao.getBookSource(sourceKey)
        }
    }

    private fun getBookSource(parameters: Map<String, List<String>>): BookSource? {
        val source = getSource(parameters)
        return source as? BookSource
    }

    private fun BaseSource.loginCheckJs(): String? {
        return when (this) {
            is BookSource -> loginCheckJs
            is RssSource -> loginCheckJs
            is HttpTTS -> loginCheckJs
            else -> null
        }
    }
}
