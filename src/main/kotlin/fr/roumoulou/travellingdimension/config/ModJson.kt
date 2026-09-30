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
 * l'identique. La lecture est tolérante (commentaires, virgules finales, clés sans guillemets,
 * clés inconnues) parce qu'un fichier commenté à la main ou hérité d'une version précédente doit
 * se charger sans broncher ; l'écriture est du JSON nu, tous les champs présents, sinon un
 * réglage laissé au défaut disparaîtrait du fichier.
 *
 * Les stores n'ont ni auto-save ni hook d'arrêt : le mod écrit lui-même, au moment où la
 * configuration change. La validation de Storify reste coupée, parce que le mod corrige une
 * valeur hors bornes au lieu de la refuser ([TravelConfig.sanitized]) : une erreur de
 * configuration est signalée, jamais fatale.
 */
object ModJson {

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
        isLenient = true
        allowStructuredMapKeys = true
        allowSpecialFloatingPointValues = true
    }

    /** Le format Storify bâti sur ce `Json`. */
    val format: JsonFormat = JsonFormat(json)

    /** Les options d'un store du mod : sans validation, sans auto-save, sans hook, sous le logger du mod. */
    fun storeConfig(readOnly: Boolean = false): StoreConfig = StoreConfig(
        withValidation = false,
        withAutoSave = false,
        withShutdownHook = false,
        readOnly = readOnly,
        loggerName = TravellingDimension.MOD_ID,
    )
}
