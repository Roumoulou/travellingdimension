// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import com.mojang.serialization.DynamicOps
import net.minecraft.core.HolderGetter
import net.minecraft.core.Registry
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackResources
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.level.material.PushReaction

/**
 * Ce qui diffère d'une version du jeu à l'autre, et que le code partagé ne peut pas écrire lui-même.
 *
 * `common` se compile contre une seule version, la dernière release servie : tout ce qu'il nomme doit exister, sous le même nom,
 * dans toutes les autres. Quand une version plus ancienne n'a pas, ou nomme autrement, ce qu'il utilise, l'appel passe par ce pont,
 * et chaque module de version (`mc-26.1`, `mc-26.2`, `mc-26.3`) en fournit l'implémentation, compilée contre son jeu. [GameVersion]
 * charge celle du jar.
 */
interface GameVersionBridge {

    /** La réaction aux pistons d'un bloc inamovible : `PushReaction.BLOCK` jusqu'en 26.2, renommée `IMMOVEABLE` en 26.3. */
    val immovablePushReaction: PushReaction

    /**
     * Les registres que le jeu charge des datapacks avant les dimensions, chacun avec le codec de ses éléments :
     * `RegistryDataLoader.WORLDGEN_REGISTRIES` jusqu'en 26.2, `WORLD_REGISTRIES` en 26.3.
     */
    val worldRegistries: List<RegistryDataLoader.RegistryData<*>>

    /** L'item du colorant de [color] : seize champs `Items.<COULEUR>_DYE` en 26.1, une collection `Items.DYE` depuis 26.2. */
    fun dyeItem(color: DyeColor): Item

    /**
     * Le datapack vanilla du jeu, lu seul, sans ce qu'un mod ou un datapack y remplace : `ServerPacksSource.createVanillaPackSource()`,
     * qui est un `PackResources` jusqu'en 26.2 et en rend un par `fullResources()` en 26.3.
     */
    fun vanillaDatapack(): PackResources

    /**
     * Un `RegistryOps` sur [delegate] dont la recherche rend, pour chaque registre, ce que [getters] rend. Ce que [getters] rend
     * est aussi le `HolderOwner` de ses références : jusqu'en 26.2 la recherche rend un `RegistryInfo`, qui les sépare, et en 26.3
     * le `HolderGetter` lui-même, qui est un `HolderOwner`.
     */
    fun <T : Any> registryOps(delegate: DynamicOps<T>, getters: (ResourceKey<out Registry<*>>) -> HolderGetter<*>): RegistryOps<T>
}
