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

    override val supportsK2: Boolean = true

    override val pluginId: String = "io.github.anschnapp.mutflow"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(MutflowIrGenerationExtension())
    }
}
