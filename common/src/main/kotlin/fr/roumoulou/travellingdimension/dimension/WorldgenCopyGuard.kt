// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.dimension

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Le garde-fou des copies, comme automate de fichiers sous `travellingdimension/generated/` : le chapitre 6.2 de
 * `01-docs/technical-docs/02-finalized/generation-de-voyage.md`.
 *
 * Une copie qui a fait échouer un chargement des registres n'est plus déclarée au lancement suivant. Son témoin de chargement,
 * `<copie>.loading`, se pose avant un chargement des registres qui la comprend ([arm]) et se lève quand ce chargement ne
 * l'accuse pas ([disarm]). Au chargement du mod, un témoin encore là désactive la copie ([review]) : sa clé, `<copie>.key.json`,
 * devient `<copie>.disabled`. La copie est de nouveau essayée quand sa clé change, ou quand le joueur supprime ce fichier. Elle
 * se refabrique alors, puisqu'elle n'a plus de clé.
 *
 * Aucun type du jeu n'y entre, et la clé s'y compare en texte, sans sa sérialisation : chaque cas est un test de l'étage 0.
 */
object WorldgenCopyGuard {

    /** Ce que lit qui ouvre un témoin de chargement resté dans le dossier. */
    private const val WITNESS_TEXT = "Travelling Dimension: the game is loading its registries with this worldgen copy. If this file outlives the loading, the copy is disabled at the next launch.\n"

    /**
     * Au chargement du mod, avant le cache : dit ce que devient la copie [copy] sous [generated], dont la clé du jour s'écrit [key].
     *
     * Un témoin resté désactive la copie, qui perd sa clé : le dossier de la copie reste en place, pour qui veut lire le fichier
     * fautif, et n'est plus déclaré. [fresh] vaut pour une copie qui se refabrique de toute façon, en développement : ce qui
     * l'accusait visait la précédente, le témoin et la désactivation tombent.
     */
    fun review(generated: Path, copy: WorldgenCopy, key: String, fresh: Boolean = false): Verdict {
        val witness = generated.resolve(copy.witnessFile)
        val disabled = generated.resolve(copy.disabledFile)
        if (fresh) {
            Files.deleteIfExists(witness)
            Files.deleteIfExists(disabled)
            return Verdict.ENABLED
        }

        var caught = false
        if (Files.exists(witness)) {
            val keyFile = generated.resolve(copy.keyFile)
            // Une copie sans clé n'a rien qui la nomme : le cache la refabrique, et rien n'est désactivé.
            if (Files.isRegularFile(keyFile)) {
                // La clé part la première : un arrêt en cours de route laisse la désactivation et son témoin, que le lancement suivant lève.
                Files.move(keyFile, disabled, StandardCopyOption.REPLACE_EXISTING)
                caught = true
            }
            Files.delete(witness)
        }

        if (!Files.isRegularFile(disabled)) return Verdict.ENABLED
        if (Files.readString(disabled).trim() == key.trim()) return if (caught) Verdict.DISABLED_NOW else Verdict.DISABLED

        Files.delete(disabled)
        return Verdict.RETRIED
    }

    /** Pose le témoin de [copy] sous [generated] : un chargement des registres qui la comprend commence. */
    fun arm(generated: Path, copy: WorldgenCopy) {
        Files.writeString(generated.resolve(copy.witnessFile), WITNESS_TEXT)
    }

    /** Lève le témoin de [copy] sous [generated] : le chargement a réussi, ou ses erreurs innocentent la copie ([clears]). */
    fun disarm(generated: Path, copy: WorldgenCopy) {
        Files.deleteIfExists(generated.resolve(copy.witnessFile))
    }

    /**
     * Dit si les erreurs d'un chargement des registres en échec innocentent [copy] : il y en a, toutes nomment un élément, et aucun
     * n'est le sien. Une erreur de registre ne nomme pas le fautif, un échec sans erreur rapportée non plus : le doute joue contre
     * la copie, parce qu'un monde qui ne s'ouvre plus coûte plus cher qu'un terrain inattendu.
     */
    fun clears(copy: WorldgenCopy, errors: Collection<LoadingError>): Boolean =
        errors.isNotEmpty() && errors.all { it.element != null && !it.element.startsWith(copy.elementPrefix) }

    /** Ce que [review] rend d'une copie. [declares] dit si elle se prépare et se déclare au jeu. */
    enum class Verdict(val declares: Boolean) {

        /** Rien ne retient la copie : elle se prépare. */
        ENABLED(true),

        /** La copie était désactivée sous une autre clé : elle est de nouveau essayée, et se refabrique. */
        RETRIED(true),

        /** Un témoin est resté du lancement précédent : la copie vient d'être désactivée. */
        DISABLED_NOW(false),

        /** La copie est désactivée depuis un lancement antérieur, et sa clé n'a pas changé. */
        DISABLED(false),
    }
}

/**
 * Une erreur d'un chargement des registres, telle que le jeu la rapporte : son registre (`minecraft:worldgen/biome`), et
 * l'élément fautif (`travellingdimension:vanilla/plains`), ou `null` quand l'erreur est celle du registre entier, une référence
 * sans cible par exemple.
 */
data class LoadingError(val registry: String, val element: String?)
