package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Test helper that compiles a Kotlin source snippet to IR in-process and returns
 * the resulting [IrModuleFragment] together with the [IrPluginContext], so
 * operator tests can walk the IR and assert on generated variants.
 *
 * The IR is captured by a test-only compiler plugin ([TestIrCaptureRegistrar])
 * loaded by the in-process [K2JVMCompiler] through the `-Xplugin` mechanism. The
 * registrar registers an [IrGenerationExtension] that records the module fragment
 * and plugin context when the compiler reaches the IR generation phase.
 */
object IrTestCompiler {

    private var capturedModule: IrModuleFragment? = null
    private var capturedContext: IrPluginContext? = null

    /** Called by [TestIrCaptureRegistrar] when the compiler reaches IR generation. */
    fun capture(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        capturedModule = moduleFragment
        capturedContext = pluginContext
    }

    /** A compiled module plus the plugin context needed to build [MutationContext]. */
    data class CompiledModule(
        val module: IrModuleFragment,
        val pluginContext: IrPluginContext
    ) {
        /**
         * Builds a [MutationContext] for the named top-level function, suitable for
         * calling operator `variants(...)`.
         */
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun contextFor(functionName: String): MutationContext {
            val fn = findFunction(functionName)
            return MutationContext(
                pluginContext = pluginContext,
                builder = DeclarationIrBuilder(pluginContext, fn.symbol),
                containingFunction = fn
            )
        }

        /** Finds the first top-level function with the given name. */
        fun findFunction(functionName: String): IrSimpleFunction {
            val functions = mutableListOf<IrSimpleFunction>()
            module.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                    if (declaration.name.asString() == functionName) {
                        functions += declaration
                    }
                    super.visitSimpleFunction(declaration)
                }
            })
            return functions.firstOrNull()
                ?: error("Function '$functionName' not found in compiled module")
        }
    }

    /**
     * Compiles [source] to IR.
     *
     * @param source Kotlin source code (a single file).
     * @param fileName name used for the temp source file (affects reported locations).
     * @param extraPlugins additional `-Xplugin` classpaths to load alongside the capture registrar.
     */
    fun compile(
        source: String,
        fileName: String = "Test.kt",
        extraPlugins: List<String> = emptyList(),
        extraClasspath: List<String> = emptyList(),
        extraArgs: List<String> = emptyList()
    ): CompiledModule {
        capturedModule = null
        capturedContext = null

        val sourceFile = File.createTempFile("mutflow-ir-test", ".kt").apply {
            writeText(source)
        }
        val outputDir = File.createTempFile("mutflow-ir-out", "").apply {
            delete()
            mkdir()
        }
        val pluginDir = createPluginDir()

        val stdlibJar = findStdlibJar()
        val classpath = (listOf(stdlibJar) + extraClasspath).joinToString(File.pathSeparator)
        val err = ByteArrayOutputStream()
        val pluginArgs = listOf("-Xplugin=${pluginDir.path}") + extraPlugins.map { "-Xplugin=$it" }
        val exitCode = K2JVMCompiler().exec(
            PrintStream(err),
            *pluginArgs.toTypedArray(),
            *extraArgs.toTypedArray(),
            "-classpath", classpath,
            "-d", outputDir.path,
            sourceFile.path
        )

        sourceFile.delete()
        outputDir.deleteRecursively()
        pluginDir.deleteRecursively()

        if (exitCode != ExitCode.OK) {
            error("Compilation failed ($exitCode):\n${err.toString()}")
        }

        val module = capturedModule ?: error("IR was not captured (compiler did not reach IR generation phase)")
        val context = capturedContext ?: error("Plugin context was not captured")
        return CompiledModule(module, context)
    }

    /**
     * Creates a temp directory that acts as a compiler plugin classpath: it holds
     * the ServiceLoader registration for [TestIrCaptureRegistrar] under
     * `META-INF/services`. The in-process compiler loads registrars from the
     * `-Xplugin` classpath, and the registrar class itself resolves through the
     * parent (test) classloader.
     */
    private fun createPluginDir(): File = createRegistrarPluginDir(
        "io.github.anschnapp.mutflow.compiler.TestIrCaptureRegistrar"
    )

    /**
     * Same trick as [createPluginDir], but for the real [MutflowCompilerPluginRegistrar]
     * (also on the test classloader, since it lives in this module's main source set).
     * Used by end-to-end regression tests that need the actual mutation transformation
     * to run, not just IR capture.
     */
    fun createRealPluginDir(): File = createRegistrarPluginDir(
        "io.github.anschnapp.mutflow.compiler.MutflowCompilerPluginRegistrar"
    )

    private fun createRegistrarPluginDir(registrarFqName: String): File {
        val dir = File.createTempFile("mutflow-ir-plugin", "").apply {
            delete()
            mkdir()
        }
        val servicesDir = File(dir, "META-INF/services").apply { mkdirs() }
        File(servicesDir, "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
            .writeText(registrarFqName)
        return dir
    }

    /**
     * Locates the kotlin-stdlib jar on the test classpath. The compiler needs it
     * to resolve builtins (Int.plus, Boolean.not, ...) referenced by the operators.
     */
    private fun findStdlibJar(): String {
        val classpath = System.getProperty("java.class.path")
        return classpath.split(File.pathSeparator)
            .firstOrNull { it.contains("kotlin-stdlib") && it.endsWith(".jar") }
            ?: error("kotlin-stdlib jar not found on test classpath")
    }

    /**
     * Locates a project module's compiled classes/jar on the test classpath by a
     * distinguishing substring (e.g. "mutflow-annotations", "mutflow-core"). Needed
     * for end-to-end tests whose source references those modules' types.
     */
    fun findProjectClasspathEntry(moduleNameFragment: String): String {
        val classpath = System.getProperty("java.class.path")
        return classpath.split(File.pathSeparator)
            .filter { it.contains(moduleNameFragment) }
            .let { candidates ->
                // Prefer a jvm-target entry over js/wasm/native/metadata ones when the
                // module is multiplatform and multiple targets are on the classpath.
                candidates.firstOrNull { "jvm" in it } ?: candidates.firstOrNull()
            }
            ?: error("$moduleNameFragment not found on test classpath")
    }
}
