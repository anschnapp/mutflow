package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI

/**
 * IR generation extension that transforms mutation points.
 *
 * This extension runs during IR lowering and:
 * 1. Finds classes annotated with @MutationTarget
 * 2. Transforms comparison operators into mutation-aware branches
 */
class MutflowIrGenerationExtension(
    private val targetPatterns: List<String> = emptyList(),
    // POC (Phase 4): when set, classes containing MutFlow.underTest get this
    // annotation synthesized onto them. See TestClassAnnotator.
    private val annotateTestClasses: String? = null
) : IrGenerationExtension {

    companion object {
        private const val DEBUG = false
        private fun debug(msg: String) {
            if (DEBUG) {
                val logFile = java.io.File("/tmp/mutflow-debug.log")
                logFile.appendText("[MUTFLOW-EXTENSION] $msg\n")
                println("[MUTFLOW-EXTENSION] $msg")
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        debug("generate() called!")
        debug("  module: ${moduleFragment.name}")
        debug("  files: ${moduleFragment.files.map { it.fileEntry.name }}")
        val transformer = MutflowIrTransformer(pluginContext, targetPatterns = targetPatterns)
        moduleFragment.transform(transformer, null)
        debug("  transformation complete")

        if (annotateTestClasses != null) {
            moduleFragment.transform(TestClassAnnotator(pluginContext, annotateTestClasses), null)
            debug("  test class annotation complete")
        }
    }
}
