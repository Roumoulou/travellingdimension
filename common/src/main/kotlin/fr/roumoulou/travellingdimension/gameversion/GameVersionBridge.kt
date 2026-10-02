// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.gameversion

import net.minecraft.world.level.material.PushReaction

/**
 * Ce qui diffère d'une version du jeu à l'autre, et que le code partagé ne peut pas écrire lui-même.
 *
 * `common` se compile contre une seule version, la dernière release servie : tout ce qu'il nomme doit exister, sous le même nom,
 * dans toutes les autres. Quand une version plus ancienne n'a pas, ou nomme autrement, ce qu'il utilise, l'appel passe par ce pont,
 * et chaque module de version (`mc-26.2`, `mc-26.3`...) en fournit l'implémentation, compilée contre son jeu. [GameVersion] charge
 * celle du jar.
 */
interface GameVersionBridge {

    /** La réaction aux pistons d'un bloc inamovible : `PushReaction.BLOCK` jusqu'en 26.2, renommée `IMMOVEABLE` en 26.3. */
    val immovablePushReaction: PushReaction
}
