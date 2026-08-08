package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * Test-only compiler plugin registrar that captures the compiled [IrModuleFragment]
 * and [IrPluginContext] into [IrTestCompiler] so operator tests can walk the IR.
 *
 * Loaded by the in-process [org.jetbrains.kotlin.cli.jvm.K2JVMCompiler] through the
 * `-Xplugin` mechanism: [IrTestCompiler] writes a ServiceLoader registration for
 * this class into a temp plugin classpath before each compilation. The class itself
 * resolves through the parent (test) classloader.
 */
@OptIn(ExperimentalCompilerApi::class)
class TestIrCaptureRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = "mutflow-ir-test-capture"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.Companion.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                IrTestCompiler.capture(moduleFragment, pluginContext)
            }
        })
    }
}
