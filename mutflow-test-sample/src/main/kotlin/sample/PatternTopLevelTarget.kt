package sample

/*
 * No annotation: this file is targeted by name, through the `sample.PatternTopLevelTargetKt`
 * pattern the sample build passes to the compiler plugin.
 */

/** -1, 0 or 1 by the sign of [value]. */
fun sign(value: Int): Int = if (value > 0) 1 else if (value < 0) -1 else 0
