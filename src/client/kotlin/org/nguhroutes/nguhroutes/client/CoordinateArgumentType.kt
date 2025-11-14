package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import net.minecraft.text.Text



data class CoordinateArgument(val coordinate: Double, val relative: Boolean = false)

val INVALID_COORDINATE = DynamicCommandExceptionType{ o: Any? -> Text.literal("Invalid coordinate argument: $o") }

class CoordinateArgumentType : ArgumentType<CoordinateArgument> {
    companion object {
        fun coordinate(): CoordinateArgumentType {
            return CoordinateArgumentType()
        }

        fun <T> getCoordinateArgument(context: CommandContext<T>, name: String): CoordinateArgument {
            return context.getArgument(name, CoordinateArgument::class.java)
        }

        fun <T> getCoordinate(context: CommandContext<T>, name: String, currentPlrCoord: Double): Double {
            val coordArg = getCoordinateArgument(context, name)
            return if (coordArg.relative) {
                currentPlrCoord + coordArg.coordinate
            } else {
                coordArg.coordinate
            }
        }
    }

    override fun parse(reader: StringReader?): CoordinateArgument {
        if (reader == null) {
            throw RuntimeException("Reader was null")
        }
        val argBeginning = reader.cursor
        try {
            if (!reader.canRead()) {
                reader.skip()
            }

            val relative = if (reader.canRead() && reader.peek() == '~') {
                reader.skip()
                true
            } else {
                false
            }

            val start = reader.cursor
            while (reader.canRead() && (Character.isDigit(reader.peek()) || reader.peek() == '.' || reader.peek() == '-')) {
                reader.skip()
            }

            if (relative && start == reader.cursor) {
                return CoordinateArgument(0.0, true)
            }

            val str = reader.string.substring(start, reader.cursor)
            return CoordinateArgument(str.toDouble(), relative)
        } catch (e: Exception) {
            reader.cursor = argBeginning
            throw INVALID_COORDINATE.createWithContext(reader, e.message)
        }
    }
}