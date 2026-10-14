package app.katty.terminal.themes

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

// The subset of Apple's property list format that `.itermcolors` files use: a dictionary of keys, each
// followed by a value that is a number, a string or another dictionary.

@Serializable
@XmlSerialName("plist")
internal class Plist(
    @XmlElement(false) val version: String? = null,
    val dict: PlistDict,
)

/** What can be in a dictionary. */
@Serializable
internal sealed interface PlistNode

@Serializable
@XmlSerialName("key")
internal class PlistKey(@XmlValue(true) val name: String) : PlistNode

@Serializable
@XmlSerialName("real")
internal class PlistReal(@XmlValue(true) val value: Double) : PlistNode

@Serializable
@XmlSerialName("string")
internal class PlistString(@XmlValue(true) val value: String) : PlistNode

/**
 * A dictionary is a sequence of nodes, in which each key is followed by its value: it is not a map until
 * [toMap] pairs them.
 */
@Serializable
@XmlSerialName("dict")
internal class PlistDict(
    val nodes: List<PlistNode> = emptyList(),
) : PlistNode {
    fun toMap(): Map<String, PlistNode> {
        val map = LinkedHashMap<String, PlistNode>()
        var i = 0
        while (i < nodes.size) {
            val key = nodes[i] as? PlistKey ?: throw IllegalArgumentException("A value without a key, at position $i of a dictionary")
            val value = nodes.getOrNull(i + 1) ?: throw IllegalArgumentException("The key '${key.name}' has no value")
            require(value !is PlistKey) { "The key '${key.name}' has no value" }
            map[key.name] = value
            i += 2
        }
        return map
    }
}

private val xml = XML {
    // The children of a dictionary are told apart by the name of their element.
    defaultPolicy {
        autoPolymorphic = true
    }
}

internal fun parsePlist(text: String): Plist = xml.decodeFromString(Plist.serializer(), text)
