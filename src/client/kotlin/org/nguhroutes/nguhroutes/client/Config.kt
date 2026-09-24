package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.OptionInstance
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
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
class SerializableBlockPosDim(val x: Int, val y: Int, val z: Int, val dimension: String) {
    constructor(blockPos: BlockPos, dimension: String) : this(blockPos.x, blockPos.y, blockPos.z, dimension)

    fun blockpos(): BlockPos {
        return BlockPos(x, y, z)
    }

    override fun toString(): String {
        return "$x $y $z" + if (dimension != "overworld") " (${dimName(dimension)})" else ""
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
    var nonether_by_default: Boolean = false
        set(value) {
            field = value
            saveConfig()
        }
    var home_location: SerializableBlockPosDim? = null
        set(value) {
            field = value
            saveConfig()
        }
    var bed_location: SerializableBlockPosDim? = null
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
                    Minecraft.getInstance().player?.displayClientMessage(
                        Component.literal("NguhRoutes config error when saving: ${e.message}")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.RED)),
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
                    context.source.sendFeedback(Component.nullToEmpty("Value of ${x.name}: ${x.get(this)}"))
                    1
                }
                .then(ClientCommandManager.literal("true")
                    .executes { context ->
                        x.set(this, true)
                        context.source.sendFeedback(Component.nullToEmpty("Set ${x.name} to: true"))
                        1
                    })
                .then(ClientCommandManager.literal("false")
                    .executes { context ->
                        x.set(this, false)
                        context.source.sendFeedback(Component.nullToEmpty("Set ${x.name} to: false"))
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

        fun addHomeArg(home: String, setHome: (SerializableBlockPosDim?) -> Unit, getHome: () -> SerializableBlockPosDim?) {
            builder = builder.then(ClientCommandManager.literal(home)
                .executes { context ->
                    context.source.sendFeedback(Component.nullToEmpty("Value of $home: ${getHome()}"))
                    1
                }
                .then(ClientCommandManager.literal("clear")
                    .executes { context ->
                        setHome(null)
                        context.source.sendFeedback(Component.nullToEmpty("Cleared $home location"))
                        1
                    })
                .then(ClientCommandManager.argument("x", CoordinateArgumentType.coordinate())
                    .then(ClientCommandManager.argument("y", CoordinateArgumentType.coordinate())
                        .then(ClientCommandManager.argument("z", CoordinateArgumentType.coordinate())
                            .executes { context ->
                                val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x).toInt()
                                val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y).toInt()
                                val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z).toInt()
                                setHome(SerializableBlockPosDim(x, y, z, "overworld"))
                                context.source.sendFeedback(Component.nullToEmpty("Set $home to $x $y $z"))
                                1
                            }
                            .then(ClientCommandManager.literal("nether")
                                .executes { context ->
                                    val x = CoordinateArgumentType.getCoordinate(context, "x", context.source.player.x).toInt()
                                    val y = CoordinateArgumentType.getCoordinate(context, "y", context.source.player.y).toInt()
                                    val z = CoordinateArgumentType.getCoordinate(context, "z", context.source.player.z).toInt()
                                    setHome(SerializableBlockPosDim(x, y, z, "the_nether"))
                                    context.source.sendFeedback(Component.nullToEmpty("Set $home to $x $y $z (Nether)"))
                                    1
                                }))))
                .then(ClientCommandManager.literal("here")
                    .executes { context ->
                        val dim = context.source.player.clientLevel.dimension().location().path
                        val pos = SerializableBlockPosDim(context.source.player.blockPosition(), dim)
                        setHome(pos)
                        context.source.sendFeedback(Component.nullToEmpty("Set $home to $pos" + if (dim != "overworld") " (${dimName(dim)})" else ""))
                        1
                    }))
        }
        addHomeArg("home", { pos -> home_location = pos }, { home_location })
        addHomeArg("bed", { pos -> bed_location = pos }, { bed_location })

        return builder
    }

    fun screenOptions(): List<OptionInstance<*>> {
        val options = mutableListOf<OptionInstance<*>>()

        fun addBooleanSetting(x: KMutableProperty1<Config, Boolean>) {
            val tooltipFactory = OptionInstance.cachedConstantTooltip<Boolean>(Component.translatable("key.nguhroutes.${x.name}.tooltip"))
            val opt = OptionInstance.createBoolean(
                "key.nguhroutes.${x.name}",
                tooltipFactory,
                x.get(this)
            ) { xNew -> x.set(this, xNew) }
            options.add(opt)
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

    fun otherWidgets(parent: Screen?): List<AbstractWidget> {
        val widgets = mutableListOf<AbstractWidget>()

        widgets.add(Button.builder(Component.translatable("key.nguhroutes.open_home_bed_config")) {
            Minecraft.getInstance().setScreen(HomeBedConfigScreen(this, parent))
        }.build())

        return widgets
    }

    fun homeBedScreenWidgets(): List<AbstractWidget> {
        val widgets = mutableListOf<AbstractWidget>()

        data class HomeButtonPair(var setButton: Button? = null, var clearButton: Button? = null)

        fun setHomeButtonTooltip(home: String, button: Button, location: SerializableBlockPosDim?) {
            button.setTooltip(Tooltip.create(Component.translatable(if (location != null) {
                "key.nguhroutes.current_${home}_location"
            } else {
                "key.nguhroutes.no_${home}_location_set"
            }, location)))
        }

        fun addSetHomeButton(home: String, pair: HomeButtonPair, setHome: (SerializableBlockPosDim?) -> Unit, getHome: () -> SerializableBlockPosDim?) {
            val homeSetter = Button.builder(Component.translatable("key.nguhroutes.set_${home}_location")) { button ->
                val player = Minecraft.getInstance().player
                if (player != null) {
                    val pos = player.blockPosition()
                    setHome(SerializableBlockPosDim(pos, player.clientLevel.dimension().location().path))
                    setHomeButtonTooltip(home, button, getHome())

                    val clearButton = pair.clearButton
                    if (clearButton != null) {
                        clearButton.active = true
                    }
                }
            }.build()
            setHomeButtonTooltip(home, homeSetter, getHome())
            if (Minecraft.getInstance().player == null) {
                homeSetter.active = false
            }
            widgets.add(homeSetter)

            pair.setButton = homeSetter
        }

        fun addClearHomeButton(home: String, pair: HomeButtonPair, getLocation: () -> SerializableBlockPosDim?, clearHome: () -> Unit) {
            val clearButton = Button.builder(Component.translatable("key.nguhroutes.clear_${home}_location")) { button ->
                clearHome()
                val setButton = pair.setButton
                if (setButton != null) {
                    setHomeButtonTooltip(home, setButton, getLocation())
                }
                button.active = false
            }.build()
            if (getLocation() == null) {
                clearButton.active = false
            }
            widgets.add(clearButton)

            pair.clearButton = clearButton
        }

        val homePair = HomeButtonPair()
        val bedPair = HomeButtonPair()

        addSetHomeButton("home", homePair, { pos -> home_location = pos }, { home_location })
        addSetHomeButton("bed", bedPair, { pos -> bed_location = pos }, { bed_location })

        addClearHomeButton("home", homePair, { home_location }, { home_location = null })
        addClearHomeButton("bed", bedPair, { bed_location }, { bed_location = null })

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
