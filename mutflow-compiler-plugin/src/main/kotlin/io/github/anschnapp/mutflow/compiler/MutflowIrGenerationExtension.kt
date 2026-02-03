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
class MutflowIrGenerationExtension : IrGenerationExtension {

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
        val transformer = MutflowIrTransformer(pluginContext)
        moduleFragment.transform(transformer, null)
        debug("  transformation complete")
    }
}
