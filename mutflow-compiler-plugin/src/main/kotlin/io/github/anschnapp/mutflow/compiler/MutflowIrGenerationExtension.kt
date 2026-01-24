package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * IR generation extension that transforms mutation points.
 *
 * This extension runs during IR lowering and:
 * 1. Finds classes annotated with @MutationTarget
 * 2. Transforms comparison operators into mutation-aware branches
 */
class MutflowIrGenerationExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val transformer = MutflowIrTransformer(pluginContext)
        moduleFragment.transform(transformer, null)
    }
}
