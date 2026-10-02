// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import net.minecraft.core.BlockPos

/** Itère toutes les positions du pavé délimité par deux coins, bornes comprises, quel que soit l'ordre des coins. */
internal inline fun forEachInBox(a: BlockPos, b: BlockPos, action: (BlockPos) -> Unit) {
    BlockPos.betweenClosed(
        minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z),
        maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z),
    ).forEach { pos -> action(pos.immutable()) }
}
