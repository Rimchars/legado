package io.legado.app.ui.association

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>(),
    ParagraphRuleOnlineImportDialog.Callback {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()
    private val onlineImportDownloader by lazy { OnlineImportDownloader(applicationContext) }
    private var pendingDownload: OnlineImportDownload? = null
    private var pendingParagraphInspection: ParagraphRuleImportInspection? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
            }
        }
        viewModel.errorLive.observe(this) {
            finallyDialog(getString(R.string.error), it)
        }
        intent.data?.let {
            val url = it.getQueryParameter("src")
            when (val route = OnlinePackageImportRoute.parse(it.scheme, it.host, it.path, url)) {
                is OnlinePackageImportRoute.ParagraphRule -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.PARAGRAPH_RULES)
                    return
                }

                is OnlinePackageImportRoute.Bubble -> {
                    downloadOnlinePackage(route, OnlineImportPayloadType.BUBBLE_PACKAGE)
                    return
                }

                is OnlinePackageImportRoute.Invalid -> {
                    finallyDialog(getString(R.string.error), route.reason)
                    return
                }

                OnlinePackageImportRoute.Other -> Unit
            }
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (it.path) {
                "/bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )

                "/rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )

                "/replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )
                "/textTocRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(url, true)
                )
                "/httpTTS" -> showDialogFragment(
                    ImportHttpTtsDialog(url, true)
                )
                "/dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(url, true)
                )
                "/theme" -> showDialogFragment(
                    ImportThemeDialog(url, true)
                )
                "/readConfig" -> viewModel.getBytes(url) { bytes ->
                    viewModel.importReadConfig(bytes, this::finallyDialog)
                }
                "/addToBookshelf" -> showDialogFragment(
                    AddToBookshelfDialog(url, true)
                )
                "/importonline" -> when (it.host) {
                    "booksource" -> showDialogFragment(
                        ImportBookSourceDialog(url, true)
                    )
                    "rsssource" -> showDialogFragment(
                        ImportRssSourceDialog(url, true)
                    )
                    "replace" -> showDialogFragment(
                        ImportReplaceRuleDialog(url, true)
                    )
                    else -> {
                        viewModel.determineType(url, this::finallyDialog)
                    }
                }
                else -> viewModel.determineType(url, this::finallyDialog)
            }
        }
    }

    private fun downloadOnlinePackage(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType,
        allowPrivateNetwork: Boolean = false
    ) {
        val sourceUrl = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> route.sourceUrl
            is OnlinePackageImportRoute.Bubble -> route.sourceUrl
            else -> return
        }
        lifecycleScope.launch {
            runCatching {
                onlineImportDownloader.download(sourceUrl, payloadType, allowPrivateNetwork)
            }.onSuccess { download ->
                if (isFinishing || isDestroyed) {
                    download.close()
                    return@onSuccess
                }
                pendingDownload?.close()
                pendingParagraphInspection = null
                pendingDownload = download
                when (route) {
                    is OnlinePackageImportRoute.ParagraphRule -> prepareParagraphRuleImport(download)
                    is OnlinePackageImportRoute.Bubble -> showBubbleImportPreview(route, download)
                    else -> discardPendingDownload(download)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is PrivateNetworkConfirmationRequiredException && !allowPrivateNetwork) {
                    showPrivateNetworkConfirmation(route, payloadType)
                } else {
                    finallyDialog(
                        getString(R.string.error),
                        error.localizedMessage ?: getString(R.string.unknown_error)
                    )
                }
            }
        }
    }

    private fun showPrivateNetworkConfirmation(
        route: OnlinePackageImportRoute,
        payloadType: OnlineImportPayloadType
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.online_import_private_network_title),
            message = getString(R.string.online_import_private_network_message),
            positiveText = getString(R.string.continue_),
            messageInContent = true,
            onPositive = {
                downloadOnlinePackage(route, payloadType, allowPrivateNetwork = true)
            },
            onDismissAction = ::finish
        )
    }

    private fun showBubbleImportPreview(
        route: OnlinePackageImportRoute.Bubble,
        download: OnlineImportDownload
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.online_import_confirm_title),
            message = buildOnlineImportPreviewMessage(route, download),
            positiveText = getString(R.string.import_),
            messageInContent = true,
            onPositive = {
                if (pendingDownload === download) pendingDownload = null
                pendingParagraphInspection = null
                importOnlinePackage(route, download)
            },
            onDismissAction = {
                discardPendingDownload(download)
            }
        )
    }

    private fun prepareParagraphRuleImport(download: OnlineImportDownload) {
        lifecycleScope.launch {
            try {
                val inspection = withContext(IO) {
                    ParagraphRulePackageImporter(appDb).inspect(download.file)
                }
                if (isFinishing || isDestroyed) {
                    discardPendingDownload(download, finishActivity = false)
                    return@launch
                }
                pendingParagraphInspection = inspection
                showDialogFragment(
                    ParagraphRuleOnlineImportDialog.create(
                        message = buildOnlineImportPreviewMessage(
                            OnlinePackageImportRoute.ParagraphRule(download.sourceUrl),
                            download,
                            inspection
                        ),
                        conflictCount = inspection.conflictCount
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                discardPendingDownload(download, finishActivity = false)
                finallyDialog(
                    getString(R.string.error),
                    error.localizedMessage ?: getString(R.string.unknown_error)
                )
            }
        }
    }

    override fun onParagraphRuleImportConfirmed(strategy: ParagraphRuleConflictStrategy) {
        val download = pendingDownload
        val inspection = pendingParagraphInspection
        if (download == null || inspection == null) {
            pendingParagraphInspection = null
            discardPendingDownload(download)
            return
        }
        pendingDownload = null
        pendingParagraphInspection = null
        importOnlinePackage(
            OnlinePackageImportRoute.ParagraphRule(download.sourceUrl),
            download,
            inspection,
            strategy
        )
    }

    override fun onParagraphRuleImportCancelled() {
        discardPendingDownload(pendingDownload)
    }

    private fun buildOnlineImportPreviewMessage(
        route: OnlinePackageImportRoute,
        download: OnlineImportDownload,
        inspection: ParagraphRuleImportInspection? = null
    ): String {
        val typeName = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> getString(R.string.paragraph_rule)
            is OnlinePackageImportRoute.Bubble -> getString(R.string.bubble_package)
            else -> return ""
        }
        val base = getString(
            R.string.online_import_preview_message,
            typeName,
            download.sourceUrl,
            download.finalUrl,
            Formatter.formatFileSize(this, download.size),
            if (download.privateNetwork) getString(R.string.yes) else getString(R.string.no)
        )
        val softLimit = when (route) {
            is OnlinePackageImportRoute.ParagraphRule -> OnlineImportPayloadType.PARAGRAPH_RULES.softLimitBytes
            is OnlinePackageImportRoute.Bubble -> OnlineImportPayloadType.BUBBLE_PACKAGE.softLimitBytes
            else -> Long.MAX_VALUE
        }
        val largeNotice = if (download.size > softLimit) {
            "\n\n${getString(R.string.large_package_read_only_notice)}"
        } else {
            ""
        }
        if (inspection == null) return base + largeNotice
        val totalCount = inspection.packageData.entries.size
        val summary = getString(
            R.string.paragraph_import_summary,
            totalCount,
            totalCount - inspection.conflictCount,
            inspection.conflictCount
        )
        return "$base$largeNotice\n\n$summary\n\n${getString(R.string.online_import_paragraph_script_warning)}"
    }

    private fun discardPendingDownload(
        download: OnlineImportDownload?,
        finishActivity: Boolean = true
    ) {
        if (download != null && pendingDownload === download) {
            pendingDownload = null
            pendingParagraphInspection = null
        }
        download?.close()
        if (finishActivity && !isFinishing) finish()
    }

    private fun importOnlinePackage(
        route: OnlinePackageImportRoute,
        download: OnlineImportDownload,
        paragraphInspection: ParagraphRuleImportInspection? = null,
        paragraphStrategy: ParagraphRuleConflictStrategy = ParagraphRuleConflictStrategy.RENAME
    ) {
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val resultMessage = when (route) {
                    is OnlinePackageImportRoute.Bubble -> withContext(IO) {
                        BubblePackageManager.importZip(download.file)
                        getString(R.string.success)
                    }

                    is OnlinePackageImportRoute.ParagraphRule -> {
                        val inspection = paragraphInspection
                            ?: throw IllegalStateException("Paragraph rule package was not prepared")
                        val result = withContext(IO) {
                            ParagraphRulePackageImporter(appDb).import(inspection, paragraphStrategy)
                        }
                        runCatching {
                            ReadBook.invalidateParagraphRuleLayout()
                            ReadBook.callBack?.upContent(resetPageOffset = false)
                            ReadBook.loadContent(resetPageOffset = false)
                        }
                        getString(
                            R.string.paragraph_import_result,
                            result.inserted,
                            result.overwritten,
                            result.skipped,
                            result.renamed
                        )
                    }

                    else -> return@launch
                }
                finallyDialog(getString(R.string.success), resultMessage)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                finallyDialog(
                    getString(R.string.error),
                    error.localizedMessage ?: getString(R.string.unknown_error)
                )
            } finally {
                download.close()
            }
        }
    }

    override fun onDestroy() {
        pendingDownload?.close()
        pendingDownload = null
        pendingParagraphInspection = null
        super.onDestroy()
    }

    private fun finallyDialog(title: String, msg: String) {
        showComposeConfirmDialog(
            title = title,
            message = msg,
            showNegative = false,
            messageInContent = true,
            onPositive = ::finish,
            onDismissAction = ::finish
        )
    }

}
