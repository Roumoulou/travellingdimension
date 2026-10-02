// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.PushReaction

/** Le pont de la lignée 26.1, déclaré dans `META-INF/services` de ce module. */
class GameVersionBridge261 : GameVersionBridge {

    override val immovablePushReaction: PushReaction = PushReaction.BLOCK

    /* Un `when` sur l'enum : le compilateur refuse une couleur oubliée. */
    override fun dyeItem(color: DyeColor): Item = when (color) {
        DyeColor.WHITE -> Items.WHITE_DYE
        DyeColor.ORANGE -> Items.ORANGE_DYE
        DyeColor.MAGENTA -> Items.MAGENTA_DYE
        DyeColor.LIGHT_BLUE -> Items.LIGHT_BLUE_DYE
        DyeColor.YELLOW -> Items.YELLOW_DYE
        DyeColor.LIME -> Items.LIME_DYE
        DyeColor.PINK -> Items.PINK_DYE
        DyeColor.GRAY -> Items.GRAY_DYE
        DyeColor.LIGHT_GRAY -> Items.LIGHT_GRAY_DYE
        DyeColor.CYAN -> Items.CYAN_DYE
        DyeColor.PURPLE -> Items.PURPLE_DYE
        DyeColor.BLUE -> Items.BLUE_DYE
        DyeColor.BROWN -> Items.BROWN_DYE
        DyeColor.GREEN -> Items.GREEN_DYE
        DyeColor.RED -> Items.RED_DYE
        DyeColor.BLACK -> Items.BLACK_DYE
    }
}
