package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout

object AppUpdate {

    val gitHubUpdate: AppUpdateInterface? by lazy {
        AppUpdateGitHub
    }
    val giteeUpdate: AppUpdateInterface by lazy {
        AppUpdateGitee
    }
    val preferredUpdate: AppUpdateInterface by lazy {
        AppUpdateGiteeFirst
    }


    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

    private object AppUpdateGiteeFirst : AppUpdateInterface {
        override fun check(scope: CoroutineScope): Coroutine<UpdateInfo> {
            return Coroutine.async(scope) {
                runCatching {
                    withTimeout(8000) {
                        AppUpdateGitee.checkNow()
                    }
                }.getOrElse { giteeError ->
                    giteeError.printOnDebug()
                    withTimeout(10000) {
                        AppUpdateGitHub.checkNow()
                    }
                }
            }.timeout(20000)
        }
    }

}
