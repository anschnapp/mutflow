package io.github.anschnapp.mutflow.compiler

/**
 * Remembers the origin of the block currently being transformed.
 *
 * The elvis/safe-call whens have a null origin in common IR; the distinguishing
 * origin sits on the enclosing block instead. [MutflowIrTransformer.visitBlock]
 * records it here before descending, so when-operators can tell an elvis/safe-call
 * apart from a plain when. Single-threaded per compilation, so a mutable object
 * is fine.
 */
internal object EnclosingOriginProvider {
    var currentOrigin: String? = null
}
