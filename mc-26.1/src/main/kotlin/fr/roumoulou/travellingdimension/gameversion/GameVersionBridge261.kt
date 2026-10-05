// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import com.mojang.serialization.DynamicOps
import com.mojang.serialization.Lifecycle
import net.minecraft.core.HolderGetter
import net.minecraft.core.HolderOwner
import net.minecraft.core.Registry
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.PushReaction
import java.util.Optional

/** Le pont de la lignée 26.1, déclaré dans `META-INF/services` de ce module. */
class GameVersionBridge261 : GameVersionBridge {

    override val immovablePushReaction: PushReaction = PushReaction.BLOCK

    override val worldRegistries: List<RegistryDataLoader.RegistryData<*>>
        get() = RegistryDataLoader.WORLDGEN_REGISTRIES

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

    override fun vanillaDatapack(): PackResources = ServerPacksSource.createVanillaPackSource()

    override fun <T : Any> registryOps(delegate: DynamicOps<T>, getters: (ResourceKey<out Registry<*>>) -> HolderGetter<*>): RegistryOps<T> =
        RegistryOps.create(delegate, object : RegistryOps.RegistryInfoLookup {
            /* La recherche rend le HolderGetter d'un registre dont le type d'élément n'est connu que de l'appelant, et qui est aussi son HolderOwner. */
            @Suppress("UNCHECKED_CAST")
            override fun <E : Any> lookup(registry: ResourceKey<out Registry<out E>>): Optional<RegistryOps.RegistryInfo<E>> {
                val getter = getters(registry) as HolderGetter<E>
                return Optional.of(RegistryOps.RegistryInfo(getter as HolderOwner<E>, getter, Lifecycle.stable()))
            }
        })
}
