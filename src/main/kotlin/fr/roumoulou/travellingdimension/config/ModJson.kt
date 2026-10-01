// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension.config

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.core.StoreConfig
import fr.roumoulou.travellingdimension.TravellingDimension
import kotlinx.serialization.json.Json

/**
 * Le JSON des fichiers du mod, `config.json` et `dev.json`, et les stores Storify qui les
 * portent.
 *
 * Un seul `Json` pour le disque et le réseau : ce qu'un serveur écrit, un client le relit à
 * l'identique. La lecture est **stricte** : ni commentaire, ni virgule finale, ni clé sans
 * guillemets, ni clé inconnue ; et Storify refuse en plus une clé déclarée deux fois, ce que
 * son `JsonFormat` ne fait que sur un `Json` ni `isLenient` ni `allowComments`. Un fichier qui
 * s'en écarte est refusé avec sa ligne. Seul le BOM est toléré, Storify le retire à la lecture.
 * L'écriture est du JSON nu, tous les champs présents, sinon un réglage laissé au défaut
 * disparaîtrait du fichier.
 *
 * Les stores n'ont ni auto-save ni hook d'arrêt : le mod écrit lui-même, au moment où la
 * configuration change. La validation de Storify est active : un store qui déclare un
 * validateur refuse à l'ouverture un fichier hors bornes ([TravelConfigValidator]), et un
 * store sans validateur, `dev.json`, n'a rien à valider.
 */
object ModJson {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        allowStructuredMapKeys = true
        allowSpecialFloatingPointValues = true
    }

    /** Le format Storify bâti sur ce `Json` : strict, clé en double comprise. */
    val format: JsonFormat = JsonFormat(json)

    /** Les options d'un store du mod : validation active, sans auto-save, sans hook, sous le logger du mod. */
    fun storeConfig(readOnly: Boolean = false): StoreConfig = StoreConfig(
        withValidation = true,
        withAutoSave = false,
        withShutdownHook = false,
        readOnly = readOnly,
        loggerName = TravellingDimension.MOD_ID,
    )
}
