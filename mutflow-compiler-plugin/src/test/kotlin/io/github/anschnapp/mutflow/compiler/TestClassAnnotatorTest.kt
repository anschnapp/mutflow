package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the annotation decision of [TestClassAnnotator]. The IR traversal it
 * feeds on is exercised end to end by example-native, which is where a wrong
 * decision would actually show up (as a class JUnit does not re-run).
 */
class TestClassAnnotatorTest {

    private fun decide(
        fileContainsMarkerCall: Boolean = true,
        classKind: ClassKind = ClassKind.CLASS,
        isAlreadyAnnotated: Boolean = false
    ) = TestClassAnnotator.shouldAnnotate(fileContainsMarkerCall, classKind, isAlreadyAnnotated)

    @Test
    fun `annotates a test class in a file that uses underTest`() {
        assertTrue(decide())
    }

    @Test
    fun `leaves classes alone when the file never calls underTest`() {
        assertFalse(decide(fileContainsMarkerCall = false))
    }

    @Test
    fun `annotates a sibling class in the same file`() {
        // The scan is file-wide on purpose: a test class may delegate its
        // underTest call to a helper declared next to it. A class with no
        // mutations of its own just produces the baseline run.
        assertTrue(decide(fileContainsMarkerCall = true))
    }

    @Test
    fun `never annotates twice`() {
        // The escape hatch: a hand-written @MutFlowTest wins, arguments intact.
        assertFalse(decide(isAlreadyAnnotated = true))
    }

    @Test
    fun `skips class kinds JUnit cannot instantiate as a test class`() {
        assertFalse(decide(classKind = ClassKind.INTERFACE))
        assertFalse(decide(classKind = ClassKind.OBJECT))
        assertFalse(decide(classKind = ClassKind.ENUM_CLASS))
        assertFalse(decide(classKind = ClassKind.ANNOTATION_CLASS))
    }
}
