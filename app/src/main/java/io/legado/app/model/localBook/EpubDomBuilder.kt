package io.legado.app.model.localBook

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.IdentityHashMap

internal class EpubDomBuilder(
    private val loadCss: (baseHref: String, href: String) -> String,
    private val resolveHref: (baseHref: String, href: String) -> String
) {

    fun build(
        doc: Document,
        body: Element,
        baseHref: String
    ): EpubDomDocument {
        val rules = collectRules(doc, body, baseHref).mapIndexed { index, rule ->
            rule.copy(order = index)
        }
        val matchedRules = matchRules(body, rules)
        val bodyElement = buildElement(
            element = body,
            baseHref = baseHref,
            parentStyle = EpubComputedStyle.empty,
            matchedRules = matchedRules,
            sourcePath = "body"
        )
        return EpubDomDocument(
            href = baseHref,
            title = doc.title().takeIf { it.isNotBlank() },
            body = bodyElement
        )
    }

    private fun collectRules(doc: Document, body: Element, baseHref: String): List<EpubCss.Rule> {
        val rules = arrayListOf<EpubCss.Rule>()
        doc.head()?.select("style")?.forEach { styleElement ->
            rules.addAll(EpubCss.parseRules(styleElement.data().ifBlank { styleElement.html() }, supportedOnly = false))
        }
        doc.head()?.select("link[href][rel~=stylesheet]")?.forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseRules(loadCss(baseHref, href), supportedOnly = false))
            }
        }
        body.select("style").forEach { styleElement ->
            rules.addAll(EpubCss.parseRules(styleElement.data().ifBlank { styleElement.html() }, supportedOnly = false))
        }
        body.select("link[href][rel~=stylesheet]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isNotBlank()) {
                rules.addAll(EpubCss.parseRules(loadCss(baseHref, href), supportedOnly = false))
            }
        }
        return rules
    }

    private fun matchRules(root: Element, rules: List<EpubCss.Rule>): IdentityHashMap<Element, MutableList<EpubCss.Rule>> {
        val matched = IdentityHashMap<Element, MutableList<EpubCss.Rule>>()
        rules.forEach { rule ->
            runCatching {
                if (root.`is`(rule.selector)) {
                    matched.getOrPut(root) { arrayListOf() }.add(rule)
                }
                root.select(rule.selector).forEach { element ->
                    matched.getOrPut(element) { arrayListOf() }.add(rule)
                }
            }
        }
        return matched
    }

    private fun buildNode(
        node: Node,
        baseHref: String,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<EpubCss.Rule>>,
        sourcePath: String
    ): EpubDomNode? {
        return when (node) {
            is TextNode -> EpubDomText(node.wholeText, sourcePath)
            is Element -> buildElement(node, baseHref, parentStyle, matchedRules, sourcePath)
            else -> null
        }
    }

    private fun buildElement(
        element: Element,
        baseHref: String,
        parentStyle: EpubComputedStyle,
        matchedRules: IdentityHashMap<Element, MutableList<EpubCss.Rule>>,
        sourcePath: String
    ): EpubDomElement {
        val style = computeStyle(element, parentStyle, matchedRules[element].orEmpty())
        val attributes = element.attributes().associate { attr ->
            val value = when (attr.key.lowercase()) {
                "src", "href", "xlink:href" -> attr.value.takeIf { it.isNotBlank() }?.let {
                    resolveHref(baseHref, it)
                } ?: attr.value
                else -> attr.value
            }
            attr.key to value
        }
        val children = element.childNodes().mapIndexedNotNull { index, child ->
            buildNode(
                node = child,
                baseHref = baseHref,
                parentStyle = style.inheritedOnly(),
                matchedRules = matchedRules,
                sourcePath = "$sourcePath/${element.normalName()}[$index]"
            )
        }
        return EpubDomElement(
            tagName = element.normalName(),
            attributes = attributes,
            style = style,
            children = children,
            sourcePath = sourcePath
        )
    }

    private fun computeStyle(
        element: Element,
        parentStyle: EpubComputedStyle,
        rules: List<EpubCss.Rule>
    ): EpubComputedStyle {
        val merged = linkedMapOf<String, EpubStyleValue>()
        parentStyle.declarations.forEach { (name, value) ->
            merged[name] = value
        }
        fun putDeclaration(
            declaration: EpubCss.Declaration,
            sourceRank: Int,
            specificity: Int,
            ruleOrder: Int
        ) {
            val value = EpubStyleValue(
                value = declaration.value,
                important = declaration.important,
                sourceRank = sourceRank + if (declaration.important) 2 else 0,
                specificity = specificity,
                ruleOrder = ruleOrder,
                declarationOrder = declaration.order
            )
            val current = merged[declaration.name]
            if (current == null || value.hasHigherPriorityThan(current)) {
                merged[declaration.name] = value
            }
        }
        rules.forEach { rule ->
            rule.declarations.forEach { declaration ->
                putDeclaration(declaration, sourceRank = 0, specificity = rule.specificity, ruleOrder = rule.order)
            }
        }
        EpubCss.parseDeclarations(element.attr("style")).forEach { declaration ->
            putDeclaration(declaration, sourceRank = 1, specificity = 1000, ruleOrder = Int.MAX_VALUE)
        }
        return EpubComputedStyle(merged)
    }
}
