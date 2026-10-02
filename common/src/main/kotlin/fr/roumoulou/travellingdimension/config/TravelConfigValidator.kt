// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import net.minecraft.resources.Identifier

/**
 * Les bornes de la configuration, vérifiées par Storify à l'ouverture du fichier et par
 * [ConfigManager.apply] sur une demande de l'écran. Une valeur hors bornes n'est jamais
 * corrigée : elle est refusée, avec son champ et sa valeur, et tous les problèmes d'une
 * configuration sont rapportés d'un coup.
 *
 * Des bornes au moins aussi larges que celles de l'écran en jeu, qui resserre les siennes :
 * sans borne ici, une valeur tapée à la main dans le fichier passerait là où l'écran l'aurait
 * refusée, et la documentation ne pourrait pas dire la vérité sur les deux chemins à la fois.
 *
 * Les identifiants (`frameBlock`, `platformBlock`, `customNoiseSettings`, `customBiomePreset`)
 * ne sont vérifiés que dans leur forme : un bloc d'un autre mod peut ne pas être enregistré
 * quand ce mod démarre, donc l'existence se résout en jeu, avec repli et avertissement.
 *
 * Les messages sont en anglais : ils finissent dans un rapport de crash, au milieu des
 * messages de Storify et du jeu.
 */
object TravelConfigValidator : Validator<TravelConfig> {

    override fun validate(data: TravelConfig, ctx: ValidationContext) {
        ctx.check(data.ratio in 2..64, "ratio", "must be between 2 and 64", data.ratio)
        ctx.check(data.mobDensity in 0.0..10.0, "mobDensity", "must be between 0 and 10", data.mobDensity)
        ctx.check(data.searchRadiusOverworld in 1..4096, "searchRadiusOverworld", "must be between 1 and 4096", data.searchRadiusOverworld)
        ctx.check(data.verticalRadius in 1..512, "verticalRadius", "must be between 1 and 512", data.verticalRadius)
        ctx.check(data.verticalWeight >= 0.0, "verticalWeight", "must be 0 or more", data.verticalWeight)
        ctx.check(data.platformMargin in 0..8, "platformMargin", "must be between 0 and 8", data.platformMargin)
        ctx.check(data.platformDepth in 0..8, "platformDepth", "must be between 0 and 8", data.platformDepth)
        ctx.check(data.clearanceMargin in 0..8, "clearanceMargin", "must be between 0 and 8", data.clearanceMargin)
        ctx.check(data.clearanceHeight in 0..16, "clearanceHeight", "must be between 0 and 16", data.clearanceHeight)
        // 0 désactive le décalage ; au-delà de la hauteur du monde, la borne ne change rien.
        ctx.check(data.buildShiftMaxOffset in 0..512, "buildShiftMaxOffset", "must be between 0 and 512", data.buildShiftMaxOffset)
        ctx.check(data.inhabitedThreshold >= 0L, "inhabitedThreshold", "must be 0 or more", data.inhabitedThreshold)
        // Un rayon négatif ferait planter le tirage d'un abri.
        ctx.check(data.rescueRadius in 1..16, "rescueRadius", "must be between 1 and 16", data.rescueRadius)
        // 41 est la borne haute assumée : au-delà, un portail créé dévaste son arrivée.
        // 3 est la borne basse pour qu'un portail reste franchissable réglage coupé.
        ctx.check(data.portalMaxSize in 3..41, "portalMaxSize", "must be between 3 and 41", data.portalMaxSize)
        ctx.check(data.netherPortalMaxSize in 3..41, "netherPortalMaxSize", "must be between 3 and 41", data.netherPortalMaxSize)
        // 0 désactive le veto, une valeur négative n'aurait aucun sens.
        ctx.check(data.redstoneVeto in 0..4096, "redstoneVeto", "must be between 0 and 4096", data.redstoneVeto)

        ctx.check(isIdentifier(data.frameBlock), "frameBlock", "must be a block identifier, such as minecraft:amethyst_block", data.frameBlock)
        ctx.check(isIdentifier(data.platformBlock), "platformBlock", "must be a block identifier, such as minecraft:calcite", data.platformBlock)
        ctx.check(isIdentifier(data.customNoiseSettings), "customNoiseSettings", "must be a registry identifier, such as minecraft:large_biomes", data.customNoiseSettings)
        ctx.check(isIdentifier(data.customBiomePreset), "customBiomePreset", "must be a registry identifier, such as minecraft:overworld", data.customBiomePreset)
    }

    /** La forme d'un identifiant, et un chemin non vide : `Identifier.tryParse("")` accepte `minecraft:`, qui ne désigne rien. */
    private fun isIdentifier(value: String): Boolean {
        val identifier = Identifier.tryParse(value) ?: return false
        return identifier.path.isNotEmpty()
    }
}
