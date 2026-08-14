package com.rfp.service.crawl

import org.w3c.dom.Element
import java.io.StringReader
import java.math.BigDecimal
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

class SitemapParser(
    private val canonicalizer: UrlCanonicalizer,
    private val maxXmlCharacters: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxXmlCharacters > 0)
    }

    fun parse(xml: String): SitemapResult {
        if (xml.length > maxXmlCharacters) throw SitemapParseException("Sitemap exceeds character limit")
        val document = try {
            secureDocumentBuilderFactory().newDocumentBuilder().apply {
                setErrorHandler(object : DefaultHandler() {
                    override fun error(exception: SAXParseException) = throw exception
                    override fun fatalError(exception: SAXParseException) = throw exception
                })
            }.parse(InputSource(StringReader(xml)))
        } catch (exception: Exception) {
            throw SitemapParseException("Unsafe or malformed sitemap", exception)
        }
        val root = document.documentElement ?: throw SitemapParseException("Sitemap has no root element")
        return when (root.localName ?: root.tagName.substringAfter(':')) {
            "sitemapindex" -> SitemapResult.Index(root.children("sitemap").map { entry ->
                SitemapReference(
                    uri = canonicalLocation(entry.requiredChildText("loc")),
                    lastModified = entry.childText("lastmod"),
                )
            })
            "urlset" -> SitemapResult.Urls(root.children("url").map { entry ->
                SitemapUrl(
                    uri = canonicalLocation(entry.requiredChildText("loc")),
                    lastModified = entry.childText("lastmod"),
                    changeFrequency = entry.childText("changefreq"),
                    priority = entry.childText("priority")?.toBigDecimalOrNull(),
                )
            })
            else -> throw SitemapParseException("Unsupported sitemap root: ${root.tagName}")
        }
    }

    private fun canonicalLocation(value: String): URI {
        val parsed = runCatching { URI(value) }.getOrNull()
            ?: throw SitemapParseException("Invalid sitemap URL")
        return canonicalizer.resolveAndNormalize(parsed, value)
            ?: throw SitemapParseException("Unsupported sitemap URL")
    }

    private fun Element.children(localName: String): List<Element> = childNodes.asSequence()
        .filterIsInstance<Element>()
        .filter { (it.localName ?: it.tagName.substringAfter(':')) == localName }
        .toList()

    private fun Element.childText(localName: String): String? = children(localName)
        .firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun Element.requiredChildText(localName: String): String = childText(localName)
        ?: throw SitemapParseException("Missing $localName")

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
}

private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
    for (index in 0 until length) yield(item(index))
}
