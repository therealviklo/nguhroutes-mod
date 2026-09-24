package org.nguhroutes.nguhroutes.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component


class ConfigScreen(val config: Config, parent: Screen) : OptionsSubScreen(
    parent,
    Minecraft.getInstance().options,
    Component.literal("NguhRoutes")
        .setStyle(Style.EMPTY
            .withBold(true)
            .withItalic(true))
        .append(Component.literal(" Config")
            .setStyle(Style.EMPTY
                .withBold(false)
                .withItalic(false)))
) {
    override fun addOptions() {
        if (list != null) {
            list?.addSmall(*config.screenOptions().toTypedArray())
            list?.addSmall(config.otherWidgets(this))
        }
    }
}