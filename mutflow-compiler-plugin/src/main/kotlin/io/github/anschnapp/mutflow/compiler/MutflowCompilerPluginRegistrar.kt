package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Entry point for the mutflow compiler plugin.
 *
 * Registers the IR generation extension that transforms mutation points
 * in @MutationTarget annotated classes.
 */
@OptIn(ExperimentalCompilerApi::class)
class MutflowCompilerPluginRegistrar : CompilerPluginRegistrar() {

    companion object {
        private const val DEBUG = false
        private fun debug(msg: String) {
            if (DEBUG) {
                val logFile = java.io.File("/tmp/mutflow-debug.log")
                logFile.appendText("[MUTFLOW-REGISTRAR] $msg\n")
                println("[MUTFLOW-REGISTRAR] $msg")
            }
        }
    }

    override val supportsK2: Boolean
        get() {
            debug("supportsK2 called -> true")
            return true
        }

    override val pluginId: String = "io.github.anschnapp.mutflow"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        debug("registerExtensions() called!")
        debug("  configuration: $configuration")
        IrGenerationExtension.registerExtension(MutflowIrGenerationExtension())
        debug("  IrGenerationExtension registered")
    }
}
