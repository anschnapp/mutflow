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

    @Test
    fun `ids of a single-file facade carry the facade name`() {
        assertEquals("com.example.Strings", MutflowIrTransformer.pointIdClassName("com.example.Strings", "/src/StringUtils.kt", multifile = false))
    }

    @Test
    fun `ids of a multifile part carry the part class, so parts sharing a facade do not collide`() {
        assertEquals(
            "com.example.Utils__StringUtilsKt",
            MutflowIrTransformer.pointIdClassName("com.example.Utils", "/src/StringUtils.kt", multifile = true)
        )
        assertEquals(
            "com.example.Utils__NumberUtilsKt",
            MutflowIrTransformer.pointIdClassName("com.example.Utils", "/src/NumberUtils.kt", multifile = true)
        )
    }
}
