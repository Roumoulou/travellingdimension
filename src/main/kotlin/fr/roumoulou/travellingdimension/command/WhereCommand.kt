package fr.roumoulou.travellingdimension.command

import com.mojang.brigadier.Command
import fr.roumoulou.travellingdimension.config.ConfigManager
import fr.roumoulou.travellingdimension.dimension.TravelDimensionKeys
import fr.roumoulou.travellingdimension.nether.NetherPortalGeometry
import fr.roumoulou.travellingdimension.nether.NetherPortalLinks
import fr.roumoulou.travellingdimension.portal.PortalCoordinates
import fr.roumoulou.travellingdimension.portal.PortalTint
import fr.roumoulou.travellingdimension.portal.TravelPortalPlacer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level

/**
 * `/where` : où suis-je, et où mène un portail bâti ici ?
 *
 * La dimension de VOYAGE ressemble à s'y méprendre à l'OVERWORLD, d'où la première ligne.
 * Les suivantes répondent à la question qui compte vraiment, et qui est la source de
 * confusion numéro un du mod : le **point idéal** est un calcul, mais **l'arrivée réelle**
 * est le portail existant le plus proche de ce point. Quand les deux diffèrent, la commande
 * le dit noir sur blanc.
 *
 * `/where cible` fait la même chose pour le bloc que l'on **regarde**, ce qui permet de lire
 * la destination d'un portail sans aller se mettre dedans.
 */
object WhereCommand {

    /** Le préfixe des messages de cette commande. */
    private const val KEYS = "commands.travellingdimension.where"

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("where")
                    .executes { ctx -> here(ctx.source) }
                    .then(Commands.literal("cible").executes { ctx -> target(ctx.source) })
            )
        }
    }

    // ─── /where ──────────────────────────────────────────────────────────────

    private fun here(source: CommandSourceStack): Int {
        val level = source.level
        val position = source.position
        val pos = BlockPos(Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z))

        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.where.current", dimensionName(level), pos.x, pos.z
            )
        }, false)

        if (!isLinked(level)) {
            source.sendSuccess({
                Component.translatable("commands.travellingdimension.where.wrong_dimension")
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        // La couleur du portail sous les pieds, s'il y en a un : la résolution la consulte
        // en premier, donc l'aperçu doit en tenir compte sous peine d'annoncer la
        // destination de quelqu'un d'autre.
        val tint = tintAround(level, pos)
        announce(source, level, pos, height = 3, tint = tint)
        return Command.SINGLE_SUCCESS
    }

    // ─── /where cible ────────────────────────────────────────────────────────

    private fun target(source: CommandSourceStack): Int {
        val pos = Targeting.targetedBlock(source, KEYS) ?: return 0
        val level = source.level

        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.where.target", pos.x, pos.y, pos.z, dimensionName(level)
            )
        }, false)

        if (!isLinked(level)) {
            source.sendSuccess({
                Component.translatable("commands.travellingdimension.where.wrong_dimension")
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            return Command.SINGLE_SUCCESS
        }

        // Un portail du NETHER visé : il a sa propre géométrie et sa propre règle, on répond
        // depuis son bloc bas-milieu et on s'arrête là.
        val netherPortal = NetherPortalGeometry.portalAt(level, pos) ?: NetherPortalGeometry.portalAt(level, pos.above())
        if (netherPortal != null) {
            source.sendSuccess({
                Component.translatable(
                    "commands.travellingdimension.where.target_nether_portal",
                    netherPortal.first.x, netherPortal.first.y, netherPortal.first.z,
                    netherPortal.second.axis1Size, netherPortal.second.axis2Size,
                )
            }, false)
            announceNether(source, level, netherPortal.first)
            return Command.SINGLE_SUCCESS
        }

        // Le geste naturel vise le bloc de cadre du bas ; le milieu du portail est celui juste
        // au-dessus. On inspecte donc les deux.
        val portal = TravelPortalPlacer.completePortalContaining(level, pos)
            ?: TravelPortalPlacer.completePortalContaining(level, pos.above())
        if (portal == null) {
            // Pas un portail : on répond quand même, en traitant le bloc visé comme l'ancre
            // d'un portail qu'on y bâtirait. C'est la question que se pose celui qui cherche
            // où poser son cadre.
            source.sendSuccess({
                Component.translatable("commands.travellingdimension.where.target_no_portal")
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            announce(source, level, pos, height = 3, tint = PortalTint.NONE)
            return Command.SINGLE_SUCCESS
        }

        source.sendSuccess({
            Component.translatable(
                "commands.travellingdimension.where.target_portal",
                portal.centre.x, portal.centre.y, portal.centre.z, portal.width, portal.height
            )
        }, false)
        if (portal.tint.isLink && ConfigManager.current.portalTints) {
            source.sendSuccess({
                Component.translatable(
                    "commands.travellingdimension.where.target_link",
                    Component.translatable("color.minecraft.${portal.tint.serializedName}")
                ).withStyle(ChatFormatting.LIGHT_PURPLE)
            }, false)
        }
        announce(source, level, portal.centre, portal.height, portal.tint)
        return Command.SINGLE_SUCCESS
    }

    // ─── Le fond commun ──────────────────────────────────────────────────────

    /**
     * Annonce le point idéal, puis **toujours** ce qui se trouve à l'arrivée.
     *
     * Deux lignes et pas une : la première est un calcul que le joueur peut refaire de tête, la
     * seconde est un fait qui dépend de ce qui est bâti. Les confondre, c'est laisser croire
     * que le mod ment.
     *
     * La seconde ligne part **dans les trois cas**, y compris quand la réponse est « rien » ou
     * « pile dessus ». Se taire dans ces deux cas laissait le joueur sans réponse là où il
     * posait justement la question, et l'absence de message ne se distingue pas d'un oubli.
     */
    private fun announce(
        source: CommandSourceStack,
        level: ServerLevel,
        anchor: BlockPos,
        height: Int,
        tint: PortalTint,
    ) {
        // L'OVERWORLD est relié aux DEUX autres, donc il répond deux fois. VOYAGE et le NETHER
        // n'ont chacun qu'un seul correspondant.
        when (level.dimension()) {
            Level.OVERWORLD -> {
                announceTravel(source, level, anchor, height, tint)
                announceNether(source, level, anchor)
            }
            TravelDimensionKeys.TRAVEL_LEVEL -> announceTravel(source, level, anchor, height, tint)
            Level.NETHER -> announceNether(source, level, anchor)
            else -> {}
        }
    }

    /**
     * **Le versant NETHER**, qui suit une règle DIFFÉRENTE de celle de VOYAGE et qu'il ne faut
     * surtout pas confondre avec elle :
     *
     * - le ratio est **8**, fixe, celui de Mojang ;
     * - la portée est **16 blocs dans le NETHER** et **128 dans l'OVERWORLD**, le même carré ;
     * - le classement est la distance **brute en trois dimensions**, dans les blocs de la
     *   dimension d'arrivée, donc **un écart de hauteur y pèse autant qu'un écart horizontal**.
     *
     * Cette dernière ligne est la source de surprise numéro un : le Y n'étant jamais converti,
     * un portail bâti très haut revient vers une cible restée à l'altitude du NETHER, et
     * quelques centaines de blocs de hauteur écrasent complètement quelques blocs d'écart au
     * sol. Deux portails voisins se départagent alors sur leur seule altitude.
     */
    private fun announceNether(source: CommandSourceStack, level: ServerLevel, anchor: BlockPos) {
        val destLevel = NetherPortalLinks.counterpart(level) ?: return
        val destIsNether = destLevel.dimension() == Level.NETHER
        val target = NetherPortalLinks.convert(level, destLevel, anchor)

        source.sendSuccess({
            Component.translatable(
                if (destIsNether) "commands.travellingdimension.where.to_nether"
                else "commands.travellingdimension.where.from_nether",
                target.x, target.y, target.z
            ).withStyle(ChatFormatting.GRAY)
        }, false)

        val actual = NetherPortalLinks.nearest(destLevel, target, destIsNether)

        source.sendSuccess({
            when {
                actual == null -> Component.translatable(
                    "commands.travellingdimension.where.nether_none",
                    NetherPortalLinks.reach(destIsNether), target.x, target.y, target.z
                ).withStyle(ChatFormatting.GRAY)

                actual == target -> Component.translatable(
                    "commands.travellingdimension.where.nether_exact",
                    actual.x, actual.y, actual.z
                ).withStyle(ChatFormatting.GREEN)

                else -> Component.translatable(
                    "commands.travellingdimension.where.nether_actual",
                    actual.x, actual.y, actual.z,
                    Mth.floor(kotlin.math.sqrt(actual.distSqr(target))),
                    kotlin.math.abs(actual.y - target.y),
                ).withStyle(ChatFormatting.GOLD)
            }
        }, false)
    }

    /** Le versant VOYAGE, celui du mod : ratio configurable, distance en blocs d'OVERWORLD. */
    private fun announceTravel(
        source: CommandSourceStack,
        level: ServerLevel,
        anchor: BlockPos,
        height: Int,
        tint: PortalTint,
    ) {
        val config = ConfigManager.current
        val toTravel = level.dimension() == Level.OVERWORLD
        val destKey = if (toTravel) TravelDimensionKeys.TRAVEL_LEVEL else Level.OVERWORLD
        val destLevel = source.server.getLevel(destKey) ?: return

        val convert: (Int) -> Int =
            if (toTravel) { coord -> PortalCoordinates.overworldToTravel(coord, config.ratio) }
            else { coord -> PortalCoordinates.travelToOverworld(coord, config.ratio) }

        val ideal = TravelPortalPlacer.idealPoint(destLevel, anchor, height, config.platformDepth, convert)

        source.sendSuccess({
            Component.translatable(
                if (toTravel) "commands.travellingdimension.where.to_travel"
                else "commands.travellingdimension.where.to_overworld",
                ideal.x, ideal.y, ideal.z, config.ratio
            ).withStyle(ChatFormatting.GRAY)
        }, false)

        // La couleur ne compte dans l'aperçu que si elle compte dans la résolution.
        val linked = if (config.portalTints && tint.isLink) {
            TravelPortalPlacer.findNearest(destLevel, ideal, toTravel, config, tint)
        } else null
        val actual = linked ?: TravelPortalPlacer.findNearest(destLevel, ideal, toTravel, config)

        source.sendSuccess({
            when {
                // Rien à portée : ce portail créera sa destination, et elle est prévisible.
                actual == null -> Component.translatable(
                    "commands.travellingdimension.where.none",
                    ideal.x, ideal.y, ideal.z
                ).withStyle(ChatFormatting.GRAY)

                // Un portail pile sur le point idéal : le calcul et le terrain s'accordent.
                actual.centre == ideal -> Component.translatable(
                    "commands.travellingdimension.where.exact",
                    actual.centre.x, actual.centre.y, actual.centre.z
                ).withStyle(ChatFormatting.GREEN)

                // Un portail ailleurs dans l'emprise : c'est LUI qu'on rejoindra. L'écart est
                // donné en blocs d'OVERWORLD, l'unité dans laquelle le mod compare.
                else -> Component.translatable(
                    "commands.travellingdimension.where.actual",
                    actual.centre.x, actual.centre.y, actual.centre.z,
                    gapInOverworldBlocks(actual.centre, ideal, toTravel, config.ratio),
                    ideal.x, ideal.z
                ).withStyle(ChatFormatting.GOLD)
            }
        }, false)
    }

    /** L'écart entre deux points d'une même dimension, en blocs d'OVERWORLD, arrondi. */
    private fun gapInOverworldBlocks(from: BlockPos, to: BlockPos, inTravel: Boolean, ratio: Int): Int =
        Mth.floor(kotlin.math.sqrt(PortalCoordinates.distanceSquared(from, to, inTravel, ratio)))

    /** La couleur du portail dans lequel se tient le joueur, pieds ou tête. */
    private fun tintAround(level: ServerLevel, pos: BlockPos): PortalTint {
        val underfoot = TravelPortalPlacer.tintAt(level, pos)
        return if (underfoot.isLink) underfoot else TravelPortalPlacer.tintAt(level, pos.above())
    }

    /** Les trois dimensions que le mod sait relier : l'OVERWORLD, VOYAGE et le NETHER. */
    private fun isLinked(level: Level): Boolean =
        level.dimension() == Level.OVERWORLD ||
                level.dimension() == TravelDimensionKeys.TRAVEL_LEVEL ||
                level.dimension() == Level.NETHER

    private fun dimensionName(level: Level): Component = when (level.dimension()) {
        TravelDimensionKeys.TRAVEL_LEVEL ->
            Component.translatable("dimension.travellingdimension.travel").withStyle(ChatFormatting.LIGHT_PURPLE)
        Level.OVERWORLD ->
            Component.translatable("dimension.travellingdimension.overworld").withStyle(ChatFormatting.GREEN)
        Level.NETHER ->
            Component.translatable("dimension.travellingdimension.nether").withStyle(ChatFormatting.RED)
        Level.END ->
            Component.translatable("dimension.travellingdimension.end").withStyle(ChatFormatting.YELLOW)
        else -> Component.literal(level.dimension().identifier().toString())
    }
}
