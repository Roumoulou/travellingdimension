// SPDX-FileCopyrightText: 2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.roumoulou.travellingdimension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode

/**
 * L'audit du mixin des tailles : chaque injection de `PortalShapeMixin` trouve-t-elle sa constante
 * dans le `PortalShape` de cette version du jeu ?
 *
 * `@ModifyConstant` vise une constante par sa valeur, dans une méthode nommée en chaîne : rien ne
 * se vérifie à la compilation, et une version du jeu qui déplace une borne laisse l'injection sans
 * cible. Le gametest du 1x1 tombe alors, sans dire laquelle des dix a manqué. Cet audit le dit : il
 * lit deux bytecodes, celui du mixin pour ce qu'il vise, celui du jeu pour ce qu'il offre.
 *
 * Il lit, il ne charge pas : une classe de mixin ne se charge pas comme une classe ordinaire, et
 * l'étage 1 n'applique aucun mixin. Comme tout l'étage 1, il est rejoué contre chaque version.
 */
class PortalShapeMixinAuditTest {

    companion object {
        private const val MIXIN = "fr/roumoulou/travellingdimension/mixin/PortalShapeMixin"
        private const val TARGET = "net/minecraft/world/level/portal/PortalShape"
        private const val MODIFY_CONSTANT = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;"
    }

    private val mixin: ClassNode by lazy { read(MIXIN) }
    private val target: ClassNode by lazy { read(TARGET) }

    @Test
    @DisplayName("chaque injection de PortalShapeMixin trouve sa constante dans PortalShape")
    fun `chaque injection trouve sa constante`() {
        val injections = injections()
        assertEquals(10, injections.size, "le mixin porte dix injections")

        val orphans = injections.filter { occurrences(it.method, it.value) == 0 }
        assertTrue(orphans.isEmpty()) { "injections sans cible dans PortalShape : " + orphans.joinToString { "${it.handler}, qui vise ${it.value} dans ${it.method}" } }
    }

    @Test
    @DisplayName("chaque constante visée n'apparaît qu'aux endroits voulus")
    fun `les occurrences sont celles du tableau`() {
        // Le tableau de la Javadoc du mixin : une injection sans ordinal remplace toutes les occurrences de sa constante dans sa méthode.
        val expected = mapOf(
            ("calculateBottomLeft" to 21) to 1,
            ("calculateWidth" to 2) to 1,
            ("calculateWidth" to 21) to 1,
            ("getDistanceUntilEdgeAboveFrame" to 21) to 1,
            ("calculateHeight" to 3) to 1,
            ("calculateHeight" to 21) to 1,
            ("getDistanceUntilTop" to 21) to 2,
            ("isValid" to 2) to 1,
            ("isValid" to 3) to 1,
            ("isValid" to 21) to 2,
        )
        val found = injections().associate { (it.method to it.value) to occurrences(it.method, it.value) }
        assertEquals(expected, found, "les occurrences de chaque constante, méthode par méthode")
    }

    /** Ce que le mixin vise, lu sur ses annotations `@ModifyConstant` : la méthode, et la constante entière à remplacer. */
    private fun injections(): List<Injection> = mixin.methods.flatMap { handler ->
        val annotations = (handler.visibleAnnotations.orEmpty() + handler.invisibleAnnotations.orEmpty()).filter { it.desc == MODIFY_CONSTANT }
        annotations.flatMap { annotation ->
            val methods = (valueOf(annotation, "method") as List<*>).map { it as String }
            val constants = (valueOf(annotation, "constant") as List<*>).map { valueOf(it as AnnotationNode, "intValue") as Int }
            methods.flatMap { method -> constants.map { Injection(handler.name, method, it) } }
        }
    }

    /** Combien de fois la constante entière [value] est poussée sur la pile dans les méthodes [method] du jeu, toutes surcharges confondues. */
    private fun occurrences(method: String, value: Int): Int =
        target.methods.filter { it.name == method }.sumOf { candidate -> candidate.instructions.count { pushedInt(it) == value } }

    /** L'entier qu'une instruction pousse, sous les quatre formes que le compilateur lui donne, ou `null`. */
    private fun pushedInt(instruction: AbstractInsnNode): Int? = when {
        instruction.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> instruction.opcode - Opcodes.ICONST_0
        instruction is IntInsnNode && (instruction.opcode == Opcodes.BIPUSH || instruction.opcode == Opcodes.SIPUSH) -> instruction.operand
        instruction is LdcInsnNode && instruction.cst is Int -> instruction.cst as Int
        else -> null
    }

    /** La valeur de l'élément [name] d'une annotation : ASM les range à plat, un nom puis sa valeur. */
    private fun valueOf(annotation: AnnotationNode, name: String): Any {
        val values = annotation.values.orEmpty()
        val index = values.indices.firstOrNull { it % 2 == 0 && values[it] == name }
            ?: error("L'annotation ${annotation.desc} n'a pas d'élément $name")
        return values[index + 1]
    }

    /** Le bytecode d'une classe, lu comme une ressource : la classe n'est pas chargée. */
    private fun read(internalName: String): ClassNode {
        val stream = javaClass.classLoader.getResourceAsStream("$internalName.class")
            ?: error("$internalName.class n'est pas sur le classpath")
        return stream.use { bytes -> ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) } }
    }

    /** Une injection du mixin : la méthode qui la porte, la méthode du jeu qu'elle vise, la constante qu'elle remplace. */
    private data class Injection(val handler: String, val method: String, val value: Int)
}
