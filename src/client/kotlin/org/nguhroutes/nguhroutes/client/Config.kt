package org.nguhroutes.nguhroutes.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

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
