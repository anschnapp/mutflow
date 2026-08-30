package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Synthesizes a runtime-retained annotation (in practice `@MutFlowTest`) onto
 * test classes, so that a test living in `commonTest` - which cannot name a
 * JVM-only type in source - still carries the annotation in the JVM bytecode
 * that JUnit's `@ClassTemplate` discovery needs.
 *
 * This is what lets a Kotlin Multiplatform project use the ordinary in-process
 * JUnit path on its `jvm()` target while the same `commonTest` sources also
 * compile for Native. See DESIGN-KOTLIN-NATIVE.md, Phase 4.
 *
 * Scope of the scan is the whole FILE, not the individual class: a test class
 * frequently delegates its `MutFlow.underTest {}` call to a helper declared
 * next to it, and a class-local scan would miss those. Over-annotating is
 * harmless by construction - a class with no `underTest` block discovers zero
 * mutations, so the extension emits the baseline run and stops.
 *
 * Only runs when the `annotateTestClasses` plugin option is set, which the
 * Gradle plugin does exclusively for the `mutatedTest` compilation of a JVM
 * target.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class TestClassAnnotator(
    private val pluginContext: IrPluginContext,
    private val annotationFqName: String,
    private val messageCollector: MessageCollector = MessageCollector.NONE,
    private val markerClassFqName: String = "io.github.anschnapp.mutflow.MutFlow",
    private val markerFunctionName: String = "underTest"
) : IrElementTransformerVoid() {

    companion object {
        /**
         * The annotation decision, split out from IR traversal so it can be
         * tested directly. A class is annotated when its file uses the marker
         * call, it is an ordinary class, and it is not already annotated by
         * hand (which is the escape hatch: writing `@MutFlowTest` yourself in
         * a JVM-only source set wins, and its arguments are preserved).
         */
        fun shouldAnnotate(
            fileContainsMarkerCall: Boolean,
            classKind: ClassKind,
            isAlreadyAnnotated: Boolean
        ): Boolean {
            if (!fileContainsMarkerCall) return false
            if (isAlreadyAnnotated) return false
            return classKind == ClassKind.CLASS
        }
    }

    private val annotationClassId = ClassId.topLevel(FqName(annotationFqName))
    private val annotationFq = FqName(annotationFqName)

    /**
     * Set on entry to each file and read by [visitClass]. Safe as mutable
     * state because IR traversal is depth-first and single-threaded per
     * module fragment: every class of a file is visited before the next file
     * is entered.
     */
    private var fileContainsMarkerCall = false

    override fun visitFile(declaration: IrFile): IrFile {
        fileContainsMarkerCall = containsMarkerCall(declaration)
        return super.visitFile(declaration)
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        val shouldAnnotate = shouldAnnotate(
            fileContainsMarkerCall = fileContainsMarkerCall,
            classKind = declaration.kind,
            isAlreadyAnnotated = declaration.hasAnnotation(annotationFq)
        )
        if (shouldAnnotate) {
            addAnnotation(declaration)
        }
        return super.visitClass(declaration)
    }

    private fun containsMarkerCall(element: IrElement): Boolean {
        var found = false
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found) return
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (found) return
                val owner = expression.symbol.owner
                if (owner.name.asString() == markerFunctionName &&
                    owner.parentClassOrNull?.fqNameWhenAvailable?.asString() == markerClassFqName
                ) {
                    found = true
                    return
                }
                expression.acceptChildrenVoid(this)
            }
        }
        element.acceptChildrenVoid(visitor)
        return found
    }

    private fun addAnnotation(declaration: IrClass) {
        // referenceClass resolves against this compilation's classpath. On a
        // Native mutatedTest there is no JUnit, so this would be null - which
        // is why the Gradle plugin never passes the option there. Warn rather
        // than fail: a missing opt-in annotation degrades to "no mutation
        // runs", not to a broken build.
        val annotationClass = pluginContext.referenceClass(annotationClassId)
        if (annotationClass == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "mutflow: annotation '$annotationFqName' is not on the compile classpath, " +
                    "test classes will not be marked for mutation runs"
            )
            return
        }
        val constructor = annotationClass.owner.constructors.firstOrNull()
        if (constructor == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "mutflow: annotation '$annotationFqName' has no constructor, skipping"
            )
            return
        }

        // Kotlin 2.4: IrClass.annotations is List<IrAnnotation>, not
        // List<IrConstructorCall>, and IrAnnotationImpl.fromSymbolOwner is the
        // factory. Every constructor parameter keeps its default value, which
        // is why the run-loop knobs come from the environment instead of from
        // annotation arguments (Phase 4.4).
        declaration.annotations = declaration.annotations + IrAnnotationImpl.fromSymbolOwner(
            annotationClass.owner.defaultType,
            constructor.symbol
        )
        messageCollector.report(
            CompilerMessageSeverity.LOGGING,
            "mutflow: marked ${declaration.fqNameWhenAvailable} with $annotationFqName"
        )
    }
}
