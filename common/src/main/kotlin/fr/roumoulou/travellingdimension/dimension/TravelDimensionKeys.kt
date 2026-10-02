// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import fr.roumoulou.travellingdimension.TravellingDimension
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/**
 * Clés de la dimension de voyage.
 *
 * La dimension elle-même est déclarée en datapack JSON :
 * - `data/travellingdimension/dimension_type/travel.json` (type Overworld, coordinate_scale 16)
 * - `data/travellingdimension/dimension/travel.json` (générateur vanilla large biomes)
 */
object TravelDimensionKeys {

    val TRAVEL_ID: Identifier = Identifier.fromNamespaceAndPath(TravellingDimension.MOD_ID, "travel")

    /** Clé du Level (monde chargé) de la dimension de voyage. */
    val TRAVEL_LEVEL: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, TRAVEL_ID)

    /** Clé du DimensionType de la dimension de voyage. */
    val TRAVEL_TYPE: ResourceKey<DimensionType> = ResourceKey.create(Registries.DIMENSION_TYPE, TRAVEL_ID)
}
