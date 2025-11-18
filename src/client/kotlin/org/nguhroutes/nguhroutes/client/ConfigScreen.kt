package org.nguhroutes.nguhroutes.client

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.option.GameOptionsScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Style
import net.minecraft.text.Text


class ConfigScreen(val config: Config, parent: Screen?) : GameOptionsScreen(
    parent,
    MinecraftClient.getInstance().options,
    Text.literal("NguhRoutes")
        .setStyle(Style.EMPTY
            .withBold(true)
            .withItalic(true))
        .append(Text.literal(" Config")
            .setStyle(Style.EMPTY
                .withBold(false)
                .withItalic(false)))
) {
    override fun addOptions() {
        if (body != null) {
            body?.addAll(*config.screenOptions().toTypedArray())
        }
    }
}