package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val MUTFLOW_TARGET_PATTERNS_KEY = CompilerConfigurationKey<List<String>>("mutflow target patterns")

// FQN of an annotation to synthesize onto test classes in files that use
// MutFlow.underTest. Unset means the annotator does not run. Set only for the
// mutatedTest compilation of a JVM target (see MutflowKmpSupport).
val MUTFLOW_ANNOTATE_TEST_CLASSES_KEY = CompilerConfigurationKey<String>("mutflow annotate test classes")

@OptIn(ExperimentalCompilerApi::class)
class MutflowCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = "io.github.anschnapp.mutflow"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = "target",
            valueDescription = "<class-or-package-pattern>",
            description = "Target class or package pattern for mutation (e.g., com.example.Calculator, com.example.service.*)",
            required = false,
            allowMultipleOccurrences = true
        ),
        CliOption(
            optionName = "annotateTestClasses",
            valueDescription = "<annotation-fqn>",
            description = "Synthesize this annotation onto test classes in files that use MutFlow.underTest",
            required = false,
            allowMultipleOccurrences = false
        )
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            "target" -> {
                val existing = configuration.get(MUTFLOW_TARGET_PATTERNS_KEY) ?: emptyList()
                configuration.put(MUTFLOW_TARGET_PATTERNS_KEY, existing + value)
            }
            "annotateTestClasses" -> configuration.put(MUTFLOW_ANNOTATE_TEST_CLASSES_KEY, value)
        }
    }
}
