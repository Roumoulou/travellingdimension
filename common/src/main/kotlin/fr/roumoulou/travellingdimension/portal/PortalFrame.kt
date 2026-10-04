// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.portal

import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.config.ConfigManager
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * **Le bloc du cadre**, résolu une fois depuis la configuration.
 *
 * Le cadre était en Budding Amethyst jusqu'au 2026-08-09, et c'était un défaut de
 * conception plus qu'un choix : en vanilla, la Budding Amethyst **ne se ramasse pas**. Elle
 * ne tombe même pas à la pioche en Soie, si bien qu'un joueur ne pouvait bâtir un portail
 * qu'à l'intérieur d'une géode, autour des blocs déjà en place. Le défaut est donc devenu
 * le **bloc d'améthyste** ordinaire, qui se fabrique avec quatre éclats.
 *
 * Le réglage [fr.roumoulou.travellingdimension.config.TravelConfig.frameBlock] accepte
 * n'importe quel identifiant de bloc : `minecraft:amethyst_block`, `minecraft:obsidian`,
 * ou celui d'un autre mod. Un identifiant inconnu ne casse rien, il retombe sur le défaut
 * avec un avertissement dans les logs — invariant du mod : une erreur de configuration est
 * signalée, jamais fatale.
 *
 * La résolution est mise en cache et recalculée dès que la configuration change, parce
 * qu'elle est consultée à chaque bloc lu pendant la détection d'un cadre.
 */
object PortalFrame {

    /** Le bloc retenu si la configuration est illisible ou nomme un bloc inexistant. */
    val FALLBACK: Block = Blocks.AMETHYST_BLOCK

    /** Le bloc de cadre courant. */
    val block: Block
        get() {
            val wanted = ConfigManager.current.frameBlock
            if (wanted != cachedId) {
                cached = resolve(wanted)
                cachedId = wanted
            }
            return cached
        }

    /** L'état posé par les constructions automatiques. */
    val state: BlockState get() = block.defaultBlockState()

    private var cachedId: String? = null
    private var cached: Block = FALLBACK

    /** [state] est-il du bloc de cadre ? La question posée à chaque bloc d'un cadre. */
    fun matches(state: BlockState): Boolean = state.`is`(block)

    /**
     * Le nom traduit du bloc, pour les messages destinés au joueur : le mod ne doit pas
     * écrire « Budding Amethyst » en dur alors que le cadre est configurable.
     */
    fun displayName(): Component = block.name

    private fun resolve(id: String): Block {
        val identifier = Identifier.tryParse(id)
        if (identifier == null) {
            TravellingDimension.LOGGER.warn(
                "frameBlock=\"{}\" n'est pas un identifiant valide, retour à {}", id, idOf(FALLBACK)
            )
            return FALLBACK
        }
        val found = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null)
        if (found == null || found == Blocks.AIR) {
            TravellingDimension.LOGGER.warn(
                "frameBlock=\"{}\" : aucun bloc de ce nom (mod absent ?), retour à {}", id, idOf(FALLBACK)
            )
            return FALLBACK
        }
        return found
    }

    private fun idOf(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()
}
