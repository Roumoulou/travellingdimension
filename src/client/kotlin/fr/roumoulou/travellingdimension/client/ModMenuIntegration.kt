package fr.roumoulou.travellingdimension.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import fr.roumoulou.travellingdimension.TravellingDimension
import fr.roumoulou.travellingdimension.client.config.TravelConfigScreen
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screens.Screen

/**
 * Branchement sur Mod Menu : c'est lui qui fournit le bouton, Cloth Config qui dessine
 * l'écran. Les deux sont **facultatifs**.
 *
 * - Mod Menu absent : Fabric ne charge même pas cette classe (l'entrypoint `modmenu`
 *   n'est lu que si le mod est là). Le mod tourne normalement, config au fichier.
 * - Mod Menu présent mais Cloth absent : on rend une fabrique qui ne produit rien,
 *   c'est la façon convenue de dire à Mod Menu « pas d'écran ». Aucune classe de Cloth
 *   n'est touchée, donc aucun `NoClassDefFoundError`.
 */
class ModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            TravellingDimension.LOGGER.info(
                "Mod Menu est présent mais pas Cloth Config : pas d'écran de configuration " +
                        "(le fichier config.json reste éditable à la main)"
            )
            return ConfigScreenFactory<Screen> { null }
        }
        return ConfigScreenFactory<Screen> { parent -> TravelConfigScreen.create(parent) }
    }
}
