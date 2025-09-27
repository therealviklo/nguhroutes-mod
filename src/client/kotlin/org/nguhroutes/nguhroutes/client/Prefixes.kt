package org.nguhroutes.nguhroutes.client

val prefixes = mapOf<String, String>("overworld" to "", "nether" to "N-")

fun getDim(code: String): String {
    for (dim in prefixes) {
        if (dim.value != "" && code.startsWith(dim.value)) {
            return dim.key
        }
    }
    return "overworld"
}