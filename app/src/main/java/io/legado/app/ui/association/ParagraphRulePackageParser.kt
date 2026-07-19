package io.legado.app.ui.association

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.utils.GSON
import java.io.File
import java.io.IOException

object ParagraphRulePackageParser {
    const val FORMAT = "legado.paragraph-rules"
    const val SCHEMA_VERSION = 1
    private const val MAX_RULES = 256
    private const val MAX_SCRIPT_CHARS = 1_048_576
    private const val MAX_VARS_PER_RULE = 256
    private const val MAX_VAR_NAME_CHARS = 200
    private const val MAX_VAR_VALUE_CHARS = 65_536

    fun parse(file: File): ParagraphRuleImportPackage {
        ParagraphRuleImportPolicy.requirePackageFile(file)
        val root = file.bufferedReader(Charsets.UTF_8).use { reader ->
            JsonParser.parseReader(reader)
        }
        return parseElement(root)
    }

    internal fun parse(raw: String): ParagraphRuleImportPackage {
        ParagraphRuleImportPolicy.requirePackageText(raw)
        return parseElement(JsonParser.parseString(raw))
    }

    private fun parseElement(root: JsonElement): ParagraphRuleImportPackage {
        val entries: List<ParagraphRuleImportEntry>
        if (root.isJsonArray) {
            val parsed = ArrayList<ParagraphRuleImportEntry>(root.asJsonArray.size())
            for (element in root.asJsonArray) {
                parsed.add(ParagraphRuleImportEntry(parseRule(element)))
            }
            entries = parsed
        } else if (root.isJsonObject && isPackageEnvelope(root.asJsonObject)) {
            entries = parseEnvelope(root.asJsonObject)
        } else if (root.isJsonObject) {
            entries = listOf(ParagraphRuleImportEntry(parseRule(root)))
        } else {
            throw IOException("Paragraph rule package must be a JSON object or array")
        }
        validateEntries(entries)
        return ParagraphRuleImportPackage(entries)
    }

    private fun parseEnvelope(root: JsonObject): List<ParagraphRuleImportEntry> {
        val formatElement = root.get("format")
        val format = if (formatElement == null || formatElement.isJsonNull) null else formatElement.asString
        if (format != FORMAT) throw IOException("Unsupported paragraph rule package format")
        val versionElement = root.get("schemaVersion")
        val schemaVersion = if (versionElement == null || versionElement.isJsonNull) null else versionElement.asInt
        if (schemaVersion != SCHEMA_VERSION) {
            throw IOException("Unsupported paragraph rule schema version: $schemaVersion")
        }
        val rules = root.get("rules")
        if (rules == null || !rules.isJsonArray) {
            throw IOException("Paragraph rule package is missing rules")
        }
        val parsed = ArrayList<ParagraphRuleImportEntry>(rules.asJsonArray.size())
        for (element in rules.asJsonArray) {
            if (!element.isJsonObject) throw IOException("Paragraph rule entry must be an object")
            val entry = element.asJsonObject
            val ruleElement = entry.get("rule")
                ?: throw IOException("Paragraph rule entry is missing rule")
            val varsIncluded = entry.has("vars")
            val varsElement = entry.get("vars")
            val vars = if (varsElement == null) emptyMap() else parseVars(varsElement)
            val exportElement = entry.get("exportId")
            var exportId: String? = null
            if (exportElement != null && !exportElement.isJsonNull) {
                exportId = exportElement.asString.trim().ifEmpty { null }
            }
            parsed.add(ParagraphRuleImportEntry(parseRule(ruleElement), vars, varsIncluded, exportId))
        }
        return parsed
    }

    private fun parseRule(element: JsonElement): ParagraphRule {
        try {
            return GSON.fromJson(element, ParagraphRule::class.java)
                ?: throw IOException("Paragraph rule entry is empty")
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException("Invalid paragraph rule entry", error)
        }
    }

    private fun parseVars(element: JsonElement): Map<String, String> {
        if (!element.isJsonObject) throw IOException("Paragraph rule vars must be an object")
        val parsed = LinkedHashMap<String, String>()
        for ((name, value) in element.asJsonObject.entrySet()) {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                throw IOException("Paragraph rule var values must be strings")
            }
            parsed[name] = value.asString
        }
        return parsed
    }

    private fun validateEntries(entries: List<ParagraphRuleImportEntry>) {
        if (entries.isEmpty()) throw IOException("Paragraph rule package is empty")
        if (entries.size > MAX_RULES) throw IOException("Paragraph rule package contains too many rules")
        for (entry in entries) {
            validateRule(entry)
        }
    }

    private fun validateRule(entry: ParagraphRuleImportEntry) {
        val rule = entry.rule
        rule.name = rule.name.trim()
        if (rule.name.isEmpty()) throw IOException("Paragraph rule name must not be empty")
        if (rule.name.length > MAX_PARAGRAPH_RULE_NAME_CHARS) {
            throw IOException("Paragraph rule name is too long")
        }
        validateText("script", rule.script, MAX_SCRIPT_CHARS, true)
        validateText("jsLib", rule.jsLib, MAX_SCRIPT_CHARS, false)
        validateText("loginUi", rule.loginUi, MAX_SCRIPT_CHARS, false)
        // BaseSource.getLoginJs() evaluates loginUrl as JavaScript. It may legitimately contain
        // spaces, tabs and line breaks, so validate it as a script rather than as a network URL.
        validateText("loginUrl", rule.loginUrl, MAX_SCRIPT_CHARS, false)
        if (entry.vars.size > MAX_VARS_PER_RULE) {
            throw IOException("Paragraph rule contains too many variables")
        }
        val exportId = entry.exportId
        if (exportId != null &&
            (exportId.length > MAX_PARAGRAPH_RULE_NAME_CHARS || exportId.any { it.isISOControl() })
        ) {
            throw IOException("Paragraph rule exportId is invalid")
        }
        for ((name, value) in entry.vars) {
            if (name.length > MAX_VAR_NAME_CHARS || name.any { it == '\u0000' }) {
                throw IOException("Paragraph rule variable name is invalid")
            }
            if (value.length > MAX_VAR_VALUE_CHARS || value.any { it == '\u0000' }) {
                throw IOException("Paragraph rule variable value is invalid")
            }
        }
    }

    private fun validateText(field: String, value: String, maxChars: Int, required: Boolean) {
        if (required && value.isBlank()) throw IOException("Paragraph rule $field must not be empty")
        if (value.length > maxChars) throw IOException("Paragraph rule $field is too long")
        if (value.any { it == '\u0000' }) throw IOException("Paragraph rule $field contains invalid characters")
    }

    private fun isPackageEnvelope(root: JsonObject): Boolean =
        root.has("format") || root.has("schemaVersion") || root.has("rules")
}
