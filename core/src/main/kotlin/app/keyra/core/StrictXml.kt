package app.keyra.core

import app.keemobile.kotpass.models.*
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** Reject extensions the selected codec cannot preserve instead of silently discarding them. */
object StrictXml : XmlContentParser {
    private val children = mapOf(
        "KeePassFile" to "Meta Root",
        "Root" to "Group DeletedObjects",
        "Meta" to "Generator HeaderHash SettingsChanged DatabaseName DatabaseNameChanged DatabaseDescription DatabaseDescriptionChanged DefaultUserName DefaultUserNameChanged MaintenanceHistoryDays Color MasterKeyChanged MasterKeyChangeRec MasterKeyChangeForce MasterKeyChangeForceOnce RecycleBinEnabled RecycleBinUUID RecycleBinChanged EntryTemplatesGroup EntryTemplatesGroupChanged HistoryMaxItems HistoryMaxSize LastSelectedGroup LastTopVisibleGroup Binaries MemoryProtection CustomIcons CustomData",
        "MemoryProtection" to "ProtectTitle ProtectUserName ProtectPassword ProtectURL ProtectNotes",
        "Binaries" to "Binary",
        "CustomIcons" to "Icon",
        "Icon" to "UUID Data Name LastModificationTime",
        "Group" to "UUID Name Notes IconID CustomIconUUID Tags IsExpanded DefaultAutoTypeSequence EnableAutoType EnableSearching LastTopVisibleEntry PreviousParentGroup Times CustomData Group Entry",
        "Entry" to "UUID IconID CustomIconUUID ForegroundColor BackgroundColor OverrideURL Tags History QualityCheck PreviousParentGroup String Binary AutoType Times CustomData",
        "Times" to "CreationTime LastModificationTime LastAccessTime ExpiryTime Expires UsageCount LocationChanged",
        "String" to "Key Value",
        "Binary" to "Key Value",
        "History" to "Entry",
        "AutoType" to "Enabled DataTransferObfuscation DefaultSequence Association",
        "Association" to "Window KeystrokeSequence",
        "CustomData" to "Item",
        "Item" to "Key Value LastModificationTime",
        "DeletedObjects" to "DeletedObject",
        "DeletedObject" to "UUID DeletionTime"
    ).mapValues { it.value.split(' ').toSet() }
    override fun unmarshalContent(xmlData: ByteArray, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        val parser = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newSAXParser().xmlReader
        val handler = object : DefaultHandler() {
            val stack = ArrayDeque<String>()
            val forceOnce = StringBuilder()
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("EXTERNAL_ENTITY") }
            override fun startElement(uri: String?, localName: String?, name: String, attributes: Attributes) {
                require(stack.size < 128) { "XML_TOO_DEEP" }
                if (stack.isEmpty()) { require(name == "KeePassFile") { "UNSUPPORTED_XML" } }
                else require(name in children[stack.last()].orEmpty()) { "UNSUPPORTED_XML_ELEMENT" }
                for (i in 0 until attributes.length) require(attributes.getQName(i) in setOf("ID", "Ref", "Protected", "ProtectInMemory", "Compressed")) { "UNSUPPORTED_XML_ATTRIBUTE" }
                stack.addLast(name)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (stack.lastOrNull() == "MasterKeyChangeForceOnce") forceOnce.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, name: String?) {
                if (name == "MasterKeyChangeForceOnce") { require(forceOnce.toString().trim().equals("False", true)) { "UNSUPPORTED_FORCE_KEY_CHANGE" }; forceOnce.clear() }
                stack.removeLast()
            }
        }
        parser.contentHandler = handler; parser.entityResolver = handler
        // The underlying XML parser also receives these bytes; DTDs must never reach it.
        require(!xmlData.hasDoctype()) { "DTD_FORBIDDEN" }
        parser.parse(InputSource(xmlData.inputStream()))
        return DefaultXmlContentParser.unmarshalContent(xmlData, contextBlock)
    }
    override fun unmarshalContent(source: InputStream, contextBlock: (Meta) -> XmlContext.Decode): DatabaseContent {
        val bytes = source.readLimited(64 * 1024 * 1024)
        return try { unmarshalContent(bytes, contextBlock) } finally { bytes.fill(0) }
    }
    override fun marshalContent(context: XmlContext.Encode, content: DatabaseContent, pretty: Boolean): String = DefaultXmlContentParser.marshalContent(context, content, pretty)
}
