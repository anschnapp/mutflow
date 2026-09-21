package io.github.anschnapp.mutflow.compiler

import kotlin.test.Test
import kotlin.test.assertEquals

class FacadeClassNameTest {

    @Test
    fun `file name plus Kt, directory dropped`() {
        assertEquals("StringUtilsKt", MutflowIrTransformer.facadeClassName("/src/main/kotlin/com/example/StringUtils.kt", null))
    }

    @Test
    fun `characters the backend replaces in the class name`() {
        assertEquals("String_utilsKt", MutflowIrTransformer.facadeClassName("string-utils.kt", null))
    }

    @Test
    fun `JvmName wins over the file name`() {
        assertEquals("Strings", MutflowIrTransformer.facadeClassName("StringUtils.kt", "Strings"))
    }
}
