package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.option.SimpleOption
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import java.util.concurrent.Executors
import kotlin.collections.mutableListOf
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties

var config = Config()

private val jsonFormat = Json { ignoreUnknownKeys = true }
private val executor = Executors.newSingleThreadExecutor()

@Serializable
class SerializableBlockPos(val x: Int, val y: Int, val z: Int) {
    constructor(blockPos: BlockPos) : this(blockPos.x, blockPos.y, blockPos.z)

    fun blockpos(): BlockPos {
        return BlockPos(x, y, z)
    }

    override fun toString(): String {
        return "$x $y $z"
    }
}

@Serializable
class Config {
    // Make sure to add the setter so that it saves automatically when a change is made
    var debug: Boolean = false
        set(value) {
            field = value
            saveConfig()
        }
    var your_doom: Boolean = false
        set(value) {
            field = value
            saveConfig()
        }
    var update_notifications: Boolean = false
        set(value) {
            field = value
            saveConfig()
        }
    var home_location: SerializableBlockPos? = null
        set(value) {
            field = value
            saveConfig()
        }
    var bed_location: SerializableBlockPos? = null
        set(value) {
            field = value
            saveConfig()
        }

    fun saveConfig() {
        executor.submit {
            try {
                val mcFolder = FabricLoader.getInstance().configDir
                val configFolder = mcFolder / "nguhroutes"

                if (!configFolder.exists()) {
                    configFolder.createDirectories()
                }

                val configFile = configFolder / "config.json"

                val jsonText = jsonFormat.encodeToString(this)
                configFile.toFile().writeText(jsonText)
            } catch (e: Exception) {
                if (debug) {
                    MinecraftClient.getInstance().player?.sendMessage(
                        Text.literal("NguhRoutes config error when saving: ${e.message}")
                            .setStyle(Style.EMPTY.withColor(Formatting.RED)),
                        false
                    )
                }
            }
        }
    }

    fun configCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        var builder = ClientCommandManager.literal("config")

        fun addBooleanSetting(x: KMutableProperty1<Config, Boolean>) {
            builder = builder.then(ClientCommandManager.literal(x.name)
                .executes { context ->
                    context.source.sendFeedback(Text.of("Value of ${x.name}: ${x.get(this)}"))
                    1
                }
                .then(ClientCommandManager.literal("true")
                    .executes { context ->
                        x.set(this, true)
                        context.source.sendFeedback(Text.of("Set ${x.name} to: true"))
                        1
                    })
                .then(ClientCommandManager.literal("false")
                    .executes { context ->
                        x.set(this, false)
                        context.source.sendFeedback(Text.of("Set ${x.name} to: false"))
                        1
                    }))
        }

        @Suppress("UNCHECKED_CAST")
        for (field in Config::class.declaredMemberProperties) {
            when {
                field.returnType.classifier == Boolean::class -> {
                    addBooleanSetting(field as KMutableProperty1<Config, Boolean>)
                }
            }
        }

        fun addHomeArg(home: String, setHome: (SerializableBlockPos?) -> Unit, getHome: () -> SerializableBlockPos?) {
            builder = builder.then(ClientCommandManager.literal(home)
                .executes { context ->
                    context.source.sendFeedback(Text.of("Value of $home: ${getHome()}"))
                    1
                }
                .then(ClientCommandManager.literal("clear")
                    .executes { context ->
                        setHome(null)
                        context.source.sendFeedback(Text.of("Cleared $home location"))
                        1
                    })
                .then(ClientCommandManager.argument("x", CoordinateArgumentType.coordinate())
                    .then(ClientCommandManager.argument("y", CoordinateArgumentType.coordinate())
                        .then(ClientCommandManager.argument("z", CoordinateArgumentType.coordinate())
                            .executes { context ->
                                val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x).toInt()
                                val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y).toInt()
                                val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z).toInt()
                                setHome(SerializableBlockPos(x, y, z))
                                context.source.sendFeedback(Text.of("Set $home to $x $y $z"))
                                1
                            })))
                .then(ClientCommandManager.literal("here")
                    .executes { context ->
                        val pos = SerializableBlockPos(context.source.player.blockPos)
                        setHome(pos)
                        context.source.sendFeedback(Text.of("Set $home to $pos"))
                        1
                    }))
        }
        addHomeArg("home", { pos -> home_location = pos }, { home_location })
        addHomeArg("bed", { pos -> bed_location = pos }, { bed_location })

        return builder
    }

    fun screenOptions(): List<SimpleOption<*>> {
        val options = mutableListOf<SimpleOption<*>>()

        fun addBooleanSetting(x: KMutableProperty1<Config, Boolean>) {
            options.add(SimpleOption.ofBoolean("key.nguhroutes.${x.name}", x.get(this)) { xNew -> x.set(this, xNew) })
        }

        @Suppress("UNCHECKED_CAST")
        for (field in Config::class.declaredMemberProperties) {
            when {
                field.returnType.classifier == Boolean::class -> {
                    addBooleanSetting(field as KMutableProperty1<Config, Boolean>)
                }
            }
        }

        return options
    }

    fun otherWidgets(parent: Screen?): List<ClickableWidget> {
        val widgets = mutableListOf<ClickableWidget>()

        widgets.add(ButtonWidget.builder(Text.translatable("key.nguhroutes.open_home_bed_config")) {
            MinecraftClient.getInstance().setScreen(HomeBedConfigScreen(this, parent))
        }.build())

        return widgets
    }

    fun homeBedScreenWidgets(): List<ClickableWidget> {
        val widgets = mutableListOf<ClickableWidget>()

        fun setHomeButtonTooltip(home: String, button: ButtonWidget, location: SerializableBlockPos?) {
            button.setTooltip(Tooltip.of(Text.translatable(if (location != null) {
                "key.nguhroutes.current_${home}_location"
            } else {
                "key.nguhroutes.no_${home}_location_set"
            }, location)))
        }

        fun addSetHomeButton(home: String, setHome: (SerializableBlockPos?) -> Unit, getHome: () -> SerializableBlockPos?): ButtonWidget {
            val homeSetter = ButtonWidget.builder(Text.translatable("key.nguhroutes.set_${home}_location")) { button ->
                val pos = MinecraftClient.getInstance().player?.blockPos
                if (pos != null) {
                    setHome(SerializableBlockPos(pos))
                    setHomeButtonTooltip(home, button, getHome())
                }
            }.build()
            setHomeButtonTooltip(home, homeSetter, getHome())
            if (MinecraftClient.getInstance().player == null) {
                homeSetter.active = false
            }
            widgets.add(homeSetter)

            return homeSetter
        }

        val homeSetter = addSetHomeButton("home", { pos -> home_location = pos }, { home_location })
        val bedSetter = addSetHomeButton("bed", { pos -> bed_location = pos }, { bed_location })

        fun addClearHomeButton(home: String, button: ButtonWidget, getLocation: () -> SerializableBlockPos?, clearHome: () -> Unit) {
            widgets.add(ButtonWidget.builder(Text.translatable("key.nguhroutes.clear_${home}_location")) {
                clearHome()
                setHomeButtonTooltip(home, button, getLocation())
            }.build())
        }

        addClearHomeButton("home", homeSetter, { home_location }, { home_location = null })
        addClearHomeButton("bed", bedSetter, { bed_location }, { bed_location = null })

        return widgets
    }
}

fun loadConfig(): Config {
    return try {
        val mcFolder = FabricLoader.getInstance().configDir
        val file = (mcFolder / "nguhroutes" / "config.json").toFile()
        if (file.exists()) {
            val jsonText = file.readText()
            jsonFormat.decodeFromString<Config>(jsonText)
        } else {
            Config()
        }
    } catch (_: Exception) {
        Config()
    }
}
