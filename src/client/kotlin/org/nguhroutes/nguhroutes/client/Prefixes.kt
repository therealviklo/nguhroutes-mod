package org.nguhroutes.nguhroutes.client

val prefixes = mapOf("overworld" to "", "the_nether" to "N-")

fun getDim(code: String): String {
    for (dim in prefixes) {
        if (dim.value != "" && code.startsWith(dim.value)) {
            return dim.key
        }
    }
    return "overworld"
}

fun getPrefix(code: String): String {
    for (dim in prefixes) {
        if (dim.value != "" && code.startsWith(dim.value)) {
            return dim.value
        }
    }
    return ""
}

/**
 * Adds a prefix to a code if the code doesn't already start with the prefix.
 */
fun addPrefix(code: String, prefix: String): String {
    return if (code.startsWith(prefix)) {
        code
    } else {
        prefix + code
    }
}