// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.client.config

import fr.roumoulou.travellingdimension.config.TravelConfig
import fr.roumoulou.travellingdimension.config.VerticalMode
import fr.roumoulou.travellingdimension.config.WorldgenMode
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Écran de configuration (Cloth Config), ouvert depuis Mod Menu.
 *
 * Deux partis pris :
 *
 * - Les réglages de génération portent `requireRestart()` : Cloth affiche alors de
 *   lui-même l'avertissement de redémarrage. Ils ne sont lus qu'à la création des
 *   mondes, les changer à chaud ne ferait rien (voir
 *   [TravelConfig.needsRestartAgainst]). Le serveur redit la même chose dans le chat
 *   après enregistrement.
 * - Un encart en tête annonce **ce qu'on est en train d'éditer** : son fichier local,
 *   ou la config du serveur. C'est le malentendu le plus facile à créer avec un écran
 *   client sur un réglage serveur.
 */
object TravelConfigScreen {

    fun create(parent: Screen): Screen {
        val base = ClientTravelConfig.displayed()
        val defaults = TravelConfig()
        val editable = ClientTravelConfig.mayEdit()

        // Le brouillon : chaque champ y dépose sa valeur, l'enregistrement le transmet
        // d'un bloc. `copy` d'une data class, donc aucun risque d'oublier un champ.
        var draft = base

        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("travellingdimension.config.title"))
            .setEditable(editable)
            .setSavingRunnable { ClientTravelConfig.save(draft) }

        val e = builder.entryBuilder()

        // ── Génération ────────────────────────────────────────────────────────────
        val worldgen = builder.getOrCreateCategory(
            Component.translatable("travellingdimension.config.category.worldgen")
        )

        worldgen.addEntry(e.startTextDescription(scopeNotice()).build())

        worldgen.addEntry(
            e.startEnumSelector(label("worldgen"), WorldgenMode::class.java, base.worldgen)
                .setDefaultValue(defaults.worldgen)
                .setEnumNameProvider { mode -> Component.translatable("travellingdimension.config.worldgen.${mode.name.lowercase()}") }
                .setTooltip(tooltip("worldgen"))
                .setSaveConsumer { draft = draft.copy(worldgen = it) }
                .requireRestart()
                .build()
        )

        worldgen.addEntry(
            e.startStrField(label("seed"), base.seed)
                .setDefaultValue(defaults.seed)
                .setTooltip(tooltip("seed"))
                .setSaveConsumer { draft = draft.copy(seed = it) }
                .requireRestart()
                .build()
        )

        worldgen.addEntry(
            e.startBooleanToggle(label("largeBiomes"), base.largeBiomes)
                .setDefaultValue(defaults.largeBiomes)
                .setTooltip(tooltip("largeBiomes"))
                .setSaveConsumer { draft = draft.copy(largeBiomes = it) }
                .requireRestart()
                .build()
        )

        worldgen.addEntry(
            e.startStrField(label("customNoiseSettings"), base.customNoiseSettings)
                .setDefaultValue(defaults.customNoiseSettings)
                .setTooltip(tooltip("customNoiseSettings"))
                .setSaveConsumer { draft = draft.copy(customNoiseSettings = it) }
                .requireRestart()
                .build()
        )

        worldgen.addEntry(
            e.startStrField(label("customBiomePreset"), base.customBiomePreset)
                .setDefaultValue(defaults.customBiomePreset)
                .setTooltip(tooltip("customBiomePreset"))
                .setSaveConsumer { draft = draft.copy(customBiomePreset = it) }
                .requireRestart()
                .build()
        )

        // ── Portails ──────────────────────────────────────────────────────────────
        val portals = builder.getOrCreateCategory(
            Component.translatable("travellingdimension.config.category.portals")
        )

        portals.addEntry(
            e.startIntSlider(label("ratio"), base.ratio, 2, 64)
                .setDefaultValue(defaults.ratio)
                .setTooltip(tooltip("ratio"))
                .setSaveConsumer { draft = draft.copy(ratio = it) }
                .build()
        )

        // Le rayon d'OVERWORLD est le SEUL réglable : celui de VOYAGE en découle, et le
        // laisser modifier séparément permettrait de briser la symétrie de la portée, donc
        // les allers-retours. L'écran affiche la valeur qui en découle dans l'infobulle.
        portals.addEntry(
            e.startIntSlider(label("searchRadiusOverworld"), base.searchRadiusOverworld, 16, 512)
                .setDefaultValue(defaults.searchRadiusOverworld)
                .setTooltip(tooltip("searchRadiusOverworld"))
                .setSaveConsumer { draft = draft.copy(searchRadiusOverworld = it) }
                .build()
        )

        portals.addEntry(
            e.startEnumSelector(label("verticalMode"), VerticalMode::class.java, base.verticalMode)
                .setDefaultValue(defaults.verticalMode)
                .setEnumNameProvider { mode ->
                    Component.translatable("travellingdimension.config.verticalMode.${mode.name.lowercase()}")
                }
                .setTooltip(tooltip("verticalMode"))
                .setSaveConsumer { draft = draft.copy(verticalMode = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("verticalRadius"), base.verticalRadius, 1, 256)
                .setDefaultValue(defaults.verticalRadius)
                .setTooltip(tooltip("verticalRadius"))
                .setSaveConsumer { draft = draft.copy(verticalRadius = it) }
                .build()
        )

        portals.addEntry(
            e.startDoubleField(label("verticalWeight"), base.verticalWeight)
                .setDefaultValue(defaults.verticalWeight)
                .setMin(0.0)
                .setMax(16.0)
                .setTooltip(tooltip("verticalWeight"))
                .setSaveConsumer { draft = draft.copy(verticalWeight = it) }
                .build()
        )

        portals.addEntry(
            e.startStrField(label("frameBlock"), base.frameBlock)
                .setDefaultValue(defaults.frameBlock)
                .setTooltip(tooltip("frameBlock"))
                .setSaveConsumer { draft = draft.copy(frameBlock = it) }
                .build()
        )

        portals.addEntry(
            e.startStrField(label("platformBlock"), base.platformBlock)
                .setDefaultValue(defaults.platformBlock)
                .setTooltip(tooltip("platformBlock"))
                .setSaveConsumer { draft = draft.copy(platformBlock = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("platformMargin"), base.platformMargin, 0, 8)
                .setDefaultValue(defaults.platformMargin)
                .setTooltip(tooltip("platformMargin"))
                .setSaveConsumer { draft = draft.copy(platformMargin = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("platformDepth"), base.platformDepth, 0, 8)
                .setDefaultValue(defaults.platformDepth)
                .setTooltip(tooltip("platformDepth"))
                .setSaveConsumer { draft = draft.copy(platformDepth = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("clearanceMargin"), base.clearanceMargin, 0, 8)
                .setDefaultValue(defaults.clearanceMargin)
                .setTooltip(tooltip("clearanceMargin"))
                .setSaveConsumer { draft = draft.copy(clearanceMargin = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("clearanceHeight"), base.clearanceHeight, 0, 16)
                .setDefaultValue(defaults.clearanceHeight)
                .setTooltip(tooltip("clearanceHeight"))
                .setSaveConsumer { draft = draft.copy(clearanceHeight = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("removeFluids"), base.removeFluids)
                .setDefaultValue(defaults.removeFluids)
                .setTooltip(tooltip("removeFluids"))
                .setSaveConsumer { draft = draft.copy(removeFluids = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("rememberEntryPortal"), base.rememberEntryPortal)
                .setDefaultValue(defaults.rememberEntryPortal)
                .setTooltip(tooltip("rememberEntryPortal"))
                .setSaveConsumer { draft = draft.copy(rememberEntryPortal = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("protectPlayerBuilds"), base.protectPlayerBuilds)
                .setDefaultValue(defaults.protectPlayerBuilds)
                .setTooltip(tooltip("protectPlayerBuilds"))
                .setSaveConsumer { draft = draft.copy(protectPlayerBuilds = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("buildShiftMaxOffset"), base.buildShiftMaxOffset, 0, 64)
                .setDefaultValue(defaults.buildShiftMaxOffset)
                .setTooltip(tooltip("buildShiftMaxOffset"))
                .setSaveConsumer { draft = draft.copy(buildShiftMaxOffset = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("redstoneVeto"), base.redstoneVeto, 0, 64)
                .setDefaultValue(defaults.redstoneVeto)
                .setTooltip(tooltip("redstoneVeto"))
                .setSaveConsumer { draft = draft.copy(redstoneVeto = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("rescueContainers"), base.rescueContainers)
                .setDefaultValue(defaults.rescueContainers)
                .setTooltip(tooltip("rescueContainers"))
                .setSaveConsumer { draft = draft.copy(rescueContainers = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("rescueRadius"), base.rescueRadius, 1, 16)
                .setDefaultValue(defaults.rescueRadius)
                .setTooltip(tooltip("rescueRadius"))
                .setSaveConsumer { draft = draft.copy(rescueRadius = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("portalFreeSize"), base.portalFreeSize)
                .setDefaultValue(defaults.portalFreeSize)
                .setTooltip(tooltip("portalFreeSize"))
                .setSaveConsumer { draft = draft.copy(portalFreeSize = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("portalMaxSize"), base.portalMaxSize, 3, 41)
                .setDefaultValue(defaults.portalMaxSize)
                .setTooltip(tooltip("portalMaxSize"))
                .setSaveConsumer { draft = draft.copy(portalMaxSize = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("netherPortalFreeSize"), base.netherPortalFreeSize)
                .setDefaultValue(defaults.netherPortalFreeSize)
                .setTooltip(tooltip("netherPortalFreeSize"))
                .setSaveConsumer { draft = draft.copy(netherPortalFreeSize = it) }
                .build()
        )

        portals.addEntry(
            e.startIntSlider(label("netherPortalMaxSize"), base.netherPortalMaxSize, 3, 41)
                .setDefaultValue(defaults.netherPortalMaxSize)
                .setTooltip(tooltip("netherPortalMaxSize"))
                .setSaveConsumer { draft = draft.copy(netherPortalMaxSize = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("portalTints"), base.portalTints)
                .setDefaultValue(defaults.portalTints)
                .setTooltip(tooltip("portalTints"))
                .setSaveConsumer { draft = draft.copy(portalTints = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("portalLocks"), base.portalLocks)
                .setDefaultValue(defaults.portalLocks)
                .setTooltip(tooltip("portalLocks"))
                .setSaveConsumer { draft = draft.copy(portalLocks = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("netherPortalPlacement"), base.netherPortalPlacement)
                .setDefaultValue(defaults.netherPortalPlacement)
                .setTooltip(tooltip("netherPortalPlacement"))
                .setSaveConsumer { draft = draft.copy(netherPortalPlacement = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("netherPortalCopySize"), base.netherPortalCopySize)
                .setDefaultValue(defaults.netherPortalCopySize)
                .setTooltip(tooltip("netherPortalCopySize"))
                .setSaveConsumer { draft = draft.copy(netherPortalCopySize = it) }
                .build()
        )

        portals.addEntry(
            e.startBooleanToggle(label("netherPortalTints"), base.netherPortalTints)
                .setDefaultValue(defaults.netherPortalTints)
                .setTooltip(tooltip("netherPortalTints"))
                .setSaveConsumer { draft = draft.copy(netherPortalTints = it) }
                .build()
        )

        // ── Gameplay ──────────────────────────────────────────────────────────────
        val gameplay = builder.getOrCreateCategory(
            Component.translatable("travellingdimension.config.category.gameplay")
        )

        gameplay.addEntry(
            e.startBooleanToggle(label("structures"), base.structures)
                .setDefaultValue(defaults.structures)
                .setTooltip(tooltip("structures"))
                .setSaveConsumer { draft = draft.copy(structures = it) }
                .build()
        )

        gameplay.addEntry(
            e.startDoubleField(label("mobDensity"), base.mobDensity)
                .setDefaultValue(defaults.mobDensity)
                .setMin(0.0)
                .setMax(10.0)
                .setTooltip(tooltip("mobDensity"))
                .setSaveConsumer { draft = draft.copy(mobDensity = it) }
                .build()
        )

        gameplay.addEntry(
            e.startBooleanToggle(label("logFallback"), base.logFallback)
                .setDefaultValue(defaults.logFallback)
                .setTooltip(tooltip("logFallback"))
                .setSaveConsumer { draft = draft.copy(logFallback = it) }
                .build()
        )

        return builder.build()
    }

    private fun label(key: String): Component =
        Component.translatable("travellingdimension.config.option.$key")

    private fun tooltip(key: String): Component =
        Component.translatable("travellingdimension.config.option.$key.tooltip")

    /** Qu'est-ce que je suis en train d'éditer, au juste ? */
    private fun scopeNotice(): Component = when {
        !ClientTravelConfig.connected ->
            Component.translatable("travellingdimension.config.scope.local").withStyle(ChatFormatting.GRAY)

        ClientTravelConfig.serverEditable ->
            Component.translatable("travellingdimension.config.scope.server").withStyle(ChatFormatting.YELLOW)

        else ->
            Component.translatable("travellingdimension.config.scope.readonly").withStyle(ChatFormatting.RED)
    }
}
