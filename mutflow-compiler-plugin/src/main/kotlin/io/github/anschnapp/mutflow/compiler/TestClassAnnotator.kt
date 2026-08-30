package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * POC (Phase 4, question 1): can the IR plugin synthesize a runtime-retained
 * annotation onto a test class, so that a `commonTest` class - which cannot
 * name JUnit types in source - still carries @MutFlowTest in the JVM bytecode?
 *
 * Trigger: the class contains at least one call to `MutFlow.underTest`.
 * Effect: the class gets [annotationFqName] added to its annotation list.
 *
 * Off unless the `annotateTestClasses` plugin option is set.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class TestClassAnnotator(
    private val pluginContext: IrPluginContext,
    private val annotationFqName: String,
    private val markerClassFqName: String = "io.github.anschnapp.mutflow.MutFlow",
    private val markerFunctionName: String = "underTest"
) : IrElementTransformerVoid() {

    private val annotationClassId = ClassId.topLevel(FqName(annotationFqName))

    override fun visitClass(declaration: IrClass): org.jetbrains.kotlin.ir.IrStatement {
        if (!declaration.hasAnnotation(FqName(annotationFqName)) && containsMarkerCall(declaration)) {
            addAnnotation(declaration)
        }
        return super.visitClass(declaration)
    }

    private fun containsMarkerCall(declaration: IrClass): Boolean {
        var found = false
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found) return
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (found) return
                val owner = expression.symbol.owner
                if (owner.name.asString() == markerFunctionName) {
                    val ownerClass = owner.parentClassOrNull?.fqNameWhenAvailable?.asString()
                    if (ownerClass == markerClassFqName) {
                        found = true
                        return
                    }
                }
                expression.acceptChildrenVoid(this)
            }
        }
        declaration.acceptChildrenVoid(visitor)
        return found
    }

    private fun addAnnotation(declaration: IrClass) {
        val annotationClass = pluginContext.referenceClass(annotationClassId)
        if (annotationClass == null) {
            System.err.println("[mutflow-poc] annotation not on compile classpath: $annotationFqName")
            return
        }
        val constructor = annotationClass.owner.constructors.firstOrNull()
        if (constructor == null) {
            System.err.println("[mutflow-poc] no constructor on $annotationFqName")
            return
        }
        declaration.annotations = declaration.annotations + IrAnnotationImpl.fromSymbolOwner(
            annotationClass.owner.defaultType,
            constructor.symbol
        )
        System.err.println("[mutflow-poc] annotated ${declaration.fqNameWhenAvailable} with $annotationFqName")
    }
}
