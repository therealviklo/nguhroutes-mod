package org.nguhroutes.nguhroutes.client

data class Colour(val red: UByte, val green: UByte, val blue: UByte, val opacity: UByte) {
    constructor(rgba: UInt) : this(
        ((rgba and 0xFF000000u) shr 24).toUByte(),
        ((rgba and 0x00FF0000u) shr 16).toUByte(),
        ((rgba and 0x0000FF00u) shr 8).toUByte(),
        (rgba and 0x000000FFu).toUByte()
    )

    constructor(rgba: Long) : this(rgba.toUInt())

    fun argb(): Int {
        return (opacity.toInt() shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()
    }

    fun withOpacity(opacity: UByte): Colour {
        return Colour(red, green, blue, opacity)
    }
}

fun hexcode(hexcode: String): Colour? {
    fun duplicateHexDigit(digit: UByte): UByte {
        return ((digit.toUInt() shl 4) or digit.toUInt()).toUByte()
    }

    if (!hexcode.startsWith('#'))
        return null
    if (hexcode.length == 9) {
        val rgb = hexcode.substring(1..8).toUIntOrNull(16) ?: return null
        return Colour(
            ((rgb and 0xFF000000u) shr 24).toUByte(),
            ((rgb and 0x00FF0000u) shr 16).toUByte(),
            ((rgb and 0x0000FF00u) shr 8).toUByte(),
            (rgb and 0x000000FFu).toUByte()
        )
    }
    if (hexcode.length == 7) {
        val rgb = hexcode.substring(1..6).toUIntOrNull(16) ?: return null
        return Colour(
            ((rgb and 0xFF0000u) shr 16).toUByte(),
            ((rgb and 0x00FF00u) shr 8).toUByte(),
            (rgb and 0x0000FFu).toUByte(),
            0xFFu
        )
    }
    if (hexcode.length == 5) {
        val rgb = hexcode.substring(1..4).toUIntOrNull(16) ?: return null
        return Colour(
            duplicateHexDigit(((rgb and 0xF000u) shr 12).toUByte()),
            duplicateHexDigit(((rgb and 0x0F00u) shr 8).toUByte()),
            duplicateHexDigit(((rgb and 0x00F0u) shr 4).toUByte()),
            duplicateHexDigit((rgb and 0x000Fu).toUByte()),
        )
    }
    if (hexcode.length == 4) {
        val rgb = hexcode.substring(1..3).toUIntOrNull(16) ?: return null
        return Colour(
            duplicateHexDigit(((rgb and 0xF00u) shr 8).toUByte()),
            duplicateHexDigit(((rgb and 0x0F0u) shr 4).toUByte()),
            duplicateHexDigit((rgb and 0x00Fu).toUByte()),
            0xFFu
        )
    }
    return null
}
