package io.legado.app.help

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.listFileDocs
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified font resolution for reader / advanced title / bubble / usehtml.
 *
 * Primary insertion form: @font:name (caller chooses per use, not a fixed global font).
 * Also accepts plain names, reader/system shortcuts, and legacy file/content paths.
 *
 * Missing fonts: tryTypeface -> null; typeface -> body/title then system default.
 */
object AppFont {

    private const val PREFIX = "@font:"
    private val fontFileRegex = Regex("(?i).*\\.[ot]tf")
    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    data class FontItem(
        /** Value recommended for storage / templates, usually `@font:FileName.ttf`. */
        val ref: String,
        val displayName: String,
        val pathOrUri: String,
    )

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val weight: Int? = null,
    )

    /**
     * Normalize user input into a stable ref.
     * - empty -> ""
     * - `@font:xxx` kept
     * - path/uri -> `@font:fileName` when possible, else raw path
     * - bare name -> `@font:name`
     */
    fun canonicalize(input: String?): String {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val lower = raw.lowercase()
        when {
            lower == "system" || lower.startsWith("system:") -> return lower
            lower == "reader" || lower == "reader:body" -> return "reader"
            lower == "reader:title" -> return "reader:title"
            lower.startsWith("package:") -> return raw
        }
        if (raw.startsWith(PREFIX, ignoreCase = true)) {
            val name = raw.substring(PREFIX.length).trim()
            return if (name.isEmpty()) "" else PREFIX + name
        }
        if (raw.isContentScheme() || raw.startsWith("/") || raw.contains(File.separator)) {
            val fileName = runCatching {
                Uri.parse(raw).lastPathSegment?.substringAfterLast('/')
                    ?: File(raw).name
            }.getOrNull()?.takeIf { it.isNotBlank() }
            return if (fileName != null && fontFileRegex.matches(fileName)) {
                PREFIX + fileName
            } else {
                raw
            }
        }
        return PREFIX + raw
    }

    /** True if [input] is an `@font:` reference or a bare font name form. */
    fun isAtFontRef(input: String?): Boolean {
        val raw = input?.trim().orEmpty()
        if (raw.startsWith(PREFIX, ignoreCase = true)) return true
        if (raw.isEmpty()) return false
        if (raw.contains("://") || raw.startsWith("/") || raw.contains(File.separator)) return false
        if (raw.lowercase().startsWith("system") || raw.lowercase().startsWith("reader") ||
            raw.lowercase().startsWith("package:")
        ) return false
        return true
    }

    fun list(context: Context = appCtx): List<FontItem> {
        val items = linkedMapOf<String, FontItem>()
        fun addDoc(doc: FileDoc) {
            if (!doc.name.matches(fontFileRegex)) return
            val path = doc.toString()
            val ref = PREFIX + doc.name
            items.putIfAbsent(doc.name.lowercase(), FontItem(ref, doc.name, path))
        }
        // App private font dir
        runCatching {
            val path = FileUtils.getPath(context.externalFiles, "font")
            File(path).listFileDocs { it.name.matches(fontFileRegex) }.forEach(::addDoc)
        }
        // User selected font folder
        val fontPath = context.getPrefString(PreferKey.fontFolder)
        if (!fontPath.isNullOrBlank()) {
            runCatching {
                if (fontPath.isContentScheme()) {
                    val uri = Uri.parse(fontPath)
                    val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    if (tree != null) {
                        FileDoc.fromDocumentFile(tree).list {
                            it.name.matches(fontFileRegex)
                        }?.forEach(::addDoc)
                    }
                } else if (File(fontPath).canRead()) {
                    FileDoc.fromFile(File(fontPath)).list {
                        it.name.matches(fontFileRegex)
                    }?.forEach(::addDoc)
                }
            }
        }
        return items.values.sortedWith { a, b -> a.displayName.cnCompare(b.displayName) }
    }

    fun typeface(
        ref: String?,
        style: Style = Style(),
        fallback: Typeface? = null,
    ): Typeface {
        val key = cacheKey(ref, style)
        typefaceCache[key]?.let { return it }
        val base = resolveBase(ref) ?: fallback ?: reader() ?: systemDefault()
        val styled = applyStyle(base, style)
        typefaceCache[key] = styled
        return styled
    }

    /**
     * Like [typeface], but returns null when an `@font:name` (or bare name) cannot be found,
     * so callers can try the next candidate. Non-name refs still resolve with system/reader rules.
     */
    fun tryTypeface(ref: String?, style: Style = Style()): Typeface? {
        val raw = ref?.trim().orEmpty()
        if (raw.isEmpty()) return systemDefault()
        val name = when {
            raw.startsWith("@font:", true) -> raw.substring(6).trim()
            isAtFontRef(raw) -> raw
            else -> null
        }
        if (name != null) {
            val path = findByName(name) ?: return null
            val base = resolvePath(path) ?: return null
            return applyStyle(base, style)
        }
        return typeface(raw, style)
    }

    fun reader(title: Boolean = false): Typeface {
        return if (title) {
            ChapterProvider.titlePaint.typeface
                ?: ChapterProvider.typeface
                ?: resolvePath(ReadBookConfig.textFont)
                ?: systemDefault()
        } else {
            ChapterProvider.contentPaint.typeface
                ?: ChapterProvider.typeface
                ?: resolvePath(ReadBookConfig.textFont)
                ?: systemDefault()
        }
    }

    fun systemDefault(): Typeface {
        return when (AppConfig.systemTypefaces) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        } ?: Typeface.DEFAULT
    }

    fun clearCache() {
        typefaceCache.clear()
    }

    fun onReaderFontChanged() {
        clearCache()
    }

    /**
     * Open the shared font picker. Result is always a canonical ref such as
     * `@font:SourceHan.ttf`, `reader`, or empty system default.
     */
    fun pick(
        fragment: Fragment,
        currentRef: String? = null,
        onPicked: (String) -> Unit,
    ) {
        AppFontPickBridge.show(fragment.childFragmentManager, currentRef, onPicked)
    }

    fun pick(
        activity: FragmentActivity,
        currentRef: String? = null,
        onPicked: (String) -> Unit,
    ) {
        AppFontPickBridge.show(activity.supportFragmentManager, currentRef, onPicked)
    }

    /**
     * Type or pick a font ref.
     * - Manual: @font:name / bare name / reader / system
     * - Button "字体库": opens shared FontSelectDialog
     * - Empty: default (caller decides fallback)
     * - Missing font at render time falls back to reader/system
     */
    fun pickOrType(
        activity: FragmentActivity,
        currentRef: String? = null,
        title: String = "字体引用",
        onPicked: (String) -> Unit,
    ) {
        val suggestions = buildList {
            add("reader")
            add("reader:title")
            add("system")
            add("system:serif")
            add("system:mono")
            list(activity).forEach { add(it.ref) }
        }
        val alertBinding = DialogEditTextBinding.inflate(activity.layoutInflater).apply {
            editView.hint = "@font:字体名.ttf"
            editView.setText(currentRef.orEmpty())
            editView.setFilterValues(suggestions)
            editView.setSelection(editView.text?.length ?: 0)
        }
        activity.alert(title) {
            setMessage(
                "手填 @font:名称（可省略扩展名），或点「字体库」选择。\n" +
                    "找不到字体时自动回退正文/系统默认。"
            )
            customView { alertBinding.root }
            neutralButton("字体库") {
                pick(activity, alertBinding.editView.text?.toString() ?: currentRef) { ref ->
                    onPicked(ref)
                }
            }
            okButton {
                onPicked(canonicalize(alertBinding.editView.text?.toString()))
            }
            cancelButton()
        }
    }

    fun pickOrType(
        fragment: Fragment,
        currentRef: String? = null,
        title: String = "字体引用",
        onPicked: (String) -> Unit,
    ) {
        pickOrType(fragment.requireActivity(), currentRef, title, onPicked)
    }

    private fun resolveBase(ref: String?): Typeface? {
        val raw = ref?.trim().orEmpty()
        if (raw.isEmpty()) return systemDefault()
        val lower = raw.lowercase()
        when {
            lower == "system" -> return systemDefault()
            lower == "system:sans" || lower == "system:sans-serif" -> return Typeface.SANS_SERIF
            lower == "system:serif" -> return Typeface.SERIF
            lower == "system:mono" || lower == "system:monospace" -> return Typeface.MONOSPACE
            lower == "reader" || lower == "reader:body" -> return reader(title = false)
            lower == "reader:title" -> return reader(title = true)
        }
        val name = when {
            raw.startsWith(PREFIX, ignoreCase = true) -> raw.substring(PREFIX.length).trim()
            isAtFontRef(raw) -> raw.trim()
            else -> null
        }
        if (name != null) {
            findByName(name)?.let { path ->
                resolvePath(path)?.let { return it }
            }
            // Treat as system family name if possible
            runCatching { Typeface.create(name, Typeface.NORMAL) }.getOrNull()?.let {
                if (it != Typeface.DEFAULT || name.equals("sans-serif", true) ||
                    name.equals("serif", true) || name.equals("monospace", true)
                ) return it
            }
            return null // missing @font:name -> caller fallback
        }
        // Legacy path / content uri
        return resolvePath(raw)
    }

    private fun findByName(name: String): String? {
        val want = name.trim()
        if (want.isEmpty()) return null
        val wantLower = want.lowercase()
        val wantBase = wantLower.substringBeforeLast('.')
        val items = list()
        items.firstOrNull { it.displayName.equals(want, true) }?.pathOrUri?.let { return it }
        items.firstOrNull {
            it.displayName.lowercase().substringBeforeLast('.') == wantBase
        }?.pathOrUri?.let { return it }
        // Also try direct path under app font dir
        val local = File(FileUtils.getPath(appCtx.externalFiles, "font"), want)
        if (local.isFile) return local.absolutePath
        return null
    }

    private fun resolvePath(fontPath: String?): Typeface? {
        val path = fontPath?.trim().orEmpty()
        if (path.isEmpty()) return null
        return runCatching {
            when {
                path.isContentScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.contentResolver.openFileDescriptor(path.toUri(), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
                }
                path.isContentScheme() -> {
                    val real = RealPathUtil.getPath(appCtx, path.toUri()) ?: return@runCatching null
                    Typeface.createFromFile(real)
                }
                else -> Typeface.createFromFile(path)
            }
        }.getOrNull()
    }

    private fun applyStyle(base: Typeface, style: Style): Typeface {
        if (style.weight != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, style.weight!!.coerceIn(1, 1000), style.italic)
        }
        val typeStyle = when {
            style.bold && style.italic -> Typeface.BOLD_ITALIC
            style.bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (typeStyle == Typeface.NORMAL) base else Typeface.create(base, typeStyle)
    }

    private fun cacheKey(ref: String?, style: Style): String {
        return buildString {
            append(ref?.trim().orEmpty())
            append('|')
            append(style.bold)
            append('|')
            append(style.italic)
            append('|')
            append(style.weight ?: -1)
            append('|')
            append(ReadBookConfig.textFont)
            append('|')
            append(AppConfig.systemTypefaces)
        }
    }
}

/**
 * Bridge fragment so any screen can open [FontSelectDialog] and receive `@font:` refs
 * without implementing CallBack themselves.
 */
class AppFontPickBridge : Fragment(), FontSelectDialog.CallBack {

    private var onPicked: ((String) -> Unit)? = null
    private var currentRef: String = ""

    override val curFontPath: String
        get() {
            val ref = currentRef
            if (ref.isEmpty() || ref.equals("system", true)) return ""
            if (ref.startsWith("@font:", true)) {
                val name = ref.substring("@font:".length).trim()
                return AppFont.list().firstOrNull {
                    it.displayName.equals(name, true) ||
                        it.displayName.substringBeforeLast('.').equals(name, true)
                }?.pathOrUri.orEmpty()
            }
            return ref
        }

    override val applySystemTypefaceOnDefault: Boolean
        get() = true

    override fun selectFont(path: String) {
        val ref = when {
            path.isBlank() -> ""
            else -> AppFont.canonicalize(path)
        }
        onPicked?.invoke(ref)
        onPicked = null
        parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val TAG = "AppFontPickBridge"
        private const val ARG_CURRENT = "current"

        fun show(
            fm: FragmentManager,
            currentRef: String?,
            onPicked: (String) -> Unit,
        ) {
            val existing = fm.findFragmentByTag(TAG) as? AppFontPickBridge
            val bridge = existing ?: AppFontPickBridge().also {
                fm.beginTransaction().add(it, TAG).commitNowAllowingStateLoss()
            }
            bridge.currentRef = currentRef.orEmpty()
            bridge.onPicked = onPicked
            bridge.showDialogFragment<FontSelectDialog>()
        }
    }
}
