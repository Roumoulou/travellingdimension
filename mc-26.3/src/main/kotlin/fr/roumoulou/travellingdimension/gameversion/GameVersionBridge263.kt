// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import net.minecraft.world.level.material.PushReaction

/** Le pont de la version 26.3, déclaré dans `META-INF/services` de ce module. */
class GameVersionBridge263 : GameVersionBridge {

    override val immovablePushReaction: PushReaction = PushReaction.IMMOVEABLE
}
