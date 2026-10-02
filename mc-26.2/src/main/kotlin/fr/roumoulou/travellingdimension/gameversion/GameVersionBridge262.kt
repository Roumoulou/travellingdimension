// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.PushReaction

/** Le pont de la version 26.2, déclaré dans `META-INF/services` de ce module. */
class GameVersionBridge262 : GameVersionBridge {

    override val immovablePushReaction: PushReaction = PushReaction.BLOCK

    override fun dyeItem(color: DyeColor): Item = Items.DYE.pick(color)
}
