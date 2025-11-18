package org.nguhroutes.nguhroutes.client

import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.reflect.KMutableProperty1

val jsonFormat = Json { ignoreUnknownKeys = true }

@Serializable
class Config {
    // Add the setter so that it saves automatically when a change is made
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

    fun saveConfig() {
        Thread {
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
                MinecraftClient.getInstance().player?.sendMessage(Text.of(e.message), false)
            }
        }.start()
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

        addBooleanSetting(Config::debug)
        addBooleanSetting(Config::your_doom)

        return builder
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
