@file:JvmName("MultifileTarget")
@file:JvmMultifileClass

package sample

/*
 * One part of the `sample.MultifileTarget` facade, targeted by that name through the pattern the
 * sample build passes to the compiler plugin; MultifileNumberTarget.kt is the other part.
 */

/** Whether [value] has fewer than two characters once trimmed. */
fun isBlankish(value: String): Boolean = value.trim().length < 2
