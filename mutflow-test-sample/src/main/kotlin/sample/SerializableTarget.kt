package sample

import kotlinx.serialization.Serializable

/**
 * A class the kotlinx.serialization plugin generates code for: `write$Self` in the class itself
 * and a `$serializer` object nested in it. It carries no annotation: the sample build targets the
 * class and, through `sample.SerializableTarget.**`, the classes nested in it. Only [isEmpty] is
 * written by hand, so it is the only code that may be mutated.
 */
@Serializable
class SerializableTarget(val name: String, val count: Int = 0) {
    fun isEmpty(): Boolean = count == 0
}
