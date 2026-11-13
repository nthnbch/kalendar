package ch.nthnbch.kalendar.ical

/** One iCalendar content line: `NAME;PARAM=value:VALUE`. */
data class IcsProp(
    val name: String,
    val params: List<Pair<String, String>> = emptyList(),
    val value: String,
) {
    fun param(key: String): String? = params.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
}

/** A `BEGIN:x … END:x` block. Kept as a mutable tree so edits preserve everything we don't understand. */
class IcsComponent(val name: String) {
    val props = mutableListOf<IcsProp>()
    val children = mutableListOf<IcsComponent>()

    fun prop(key: String): IcsProp? = props.firstOrNull { it.name.equals(key, ignoreCase = true) }
    fun props(key: String): List<IcsProp> = props.filter { it.name.equals(key, ignoreCase = true) }
    fun children(key: String): List<IcsComponent> = children.filter { it.name.equals(key, ignoreCase = true) }

    fun remove(key: String) {
        props.removeAll { it.name.equals(key, ignoreCase = true) }
    }

    /** Replaces the property in place (keeping its position) or appends it. */
    fun set(prop: IcsProp) {
        val index = props.indexOfFirst { it.name.equals(prop.name, ignoreCase = true) }
        remove(prop.name)
        if (index in 0..props.size) props.add(index, prop) else props.add(prop)
    }
}

object Ics {

    fun parse(text: String): IcsComponent? {
        val stack = ArrayDeque<IcsComponent>()
        var root: IcsComponent? = null
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val prop = parseLine(line) ?: continue
            when {
                prop.name.equals("BEGIN", ignoreCase = true) -> {
                    val component = IcsComponent(prop.value.trim().uppercase())
                    stack.lastOrNull()?.children?.add(component)
                    if (root == null) root = component
                    stack.addLast(component)
                }
                prop.name.equals("END", ignoreCase = true) -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.lastOrNull()?.props?.add(prop)
            }
        }
        return root
    }

    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var started = false
        for (raw in text.split("\r\n", "\n", "\r")) {
            if (started && raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) {
                current.append(raw, 1, raw.length)
            } else {
                if (started) out.add(current.toString())
                current.setLength(0)
                current.append(raw)
                started = true
            }
        }
        if (started) out.add(current.toString())
        return out
    }

    private fun parseLine(line: String): IcsProp? {
        // Find the first ':' outside of double quotes: everything before is name + params.
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) {
                colon = i
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val parts = ArrayList<String>()
        val sb = StringBuilder()
        inQuotes = false
        for (c in head) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(c)
                }
                c == ';' && !inQuotes -> {
                    parts.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        parts.add(sb.toString())

        // Some producers prefix names with a group ("item1.X-FOO"): irrelevant for calendars but harmless.
        val name = parts[0].trim().uppercase()
        if (name.isEmpty()) return null
        val params = parts.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq).trim().uppercase() to p.substring(eq + 1).trim().removeSurrounding("\"")
        }
        return IcsProp(name, params, value)
    }

    fun serialize(root: IcsComponent): String {
        val sb = StringBuilder()
        write(root, sb)
        return sb.toString()
    }

    private fun write(component: IcsComponent, sb: StringBuilder) {
        fold("BEGIN:${component.name}", sb)
        for (p in component.props) {
            val line = StringBuilder(p.name)
            for ((k, v) in p.params) {
                line.append(';').append(k).append('=')
                if (v.any { it == ':' || it == ';' || it == ',' }) line.append('"').append(v).append('"') else line.append(v)
            }
            line.append(':').append(p.value)
            fold(line.toString(), sb)
        }
        for (child in component.children) write(child, sb)
        fold("END:${component.name}", sb)
    }

    /** Folds at 75 octets as RFC 5545 asks, never splitting a multi-byte character. */
    private fun fold(line: String, sb: StringBuilder) {
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (octets + size > limit) {
                sb.append("\r\n ")
                octets = 0
                limit = 74
            }
            sb.appendCodePoint(cp)
            octets += size
            i += Character.charCount(cp)
        }
        sb.append("\r\n")
    }

    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
        }
    }

    fun unescape(text: String): String {
        if (text.indexOf('\\') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val n = text[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
