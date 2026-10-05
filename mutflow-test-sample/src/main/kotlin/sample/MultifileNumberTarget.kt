@file:JvmName("MultifileTarget")
@file:JvmMultifileClass

package sample

/* The other part of the `sample.MultifileTarget` facade; see MultifileStringTarget.kt. */

/** Whether [value] is below ten. */
fun isSmall(value: Int): Boolean = value < 10
