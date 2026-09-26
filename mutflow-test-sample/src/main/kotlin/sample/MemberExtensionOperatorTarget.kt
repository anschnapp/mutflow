package sample

import io.github.anschnapp.mutflow.MutationTarget
import io.github.anschnapp.mutflow.SuppressMutations
import sample.MoneyOperators.minus

/** An amount of money, whose arithmetic is declared in [MoneyOperators] rather than on the class. */
data class Money(val cents: Long)

/** A count of items, whose operators [MoneyOperators] declares first, under the same names. */
data class Items(val count: Int)

/**
 * Operators declared as extensions inside an object. A call to one passes the object as
 * dispatch receiver in front of its two operands, and every name is declared for [Items] first:
 * a variant must keep the receiver and both operands, and call the overload for [Money], not the
 * first one of that name. `Money + Int` has no `Money - Int` to swap to. The object is not a
 * target, so the only points are the calls in [MemberExtensionOperatorTarget].
 */
object MoneyOperators {
    operator fun Items.plus(that: Items): Items = Items(count + that.count)
    operator fun Items.minus(that: Items): Items = Items(count - that.count)
    operator fun Items.times(factor: Int): Items = Items(count * factor)
    operator fun Items.div(parts: Int): Items = Items(count / parts)
    operator fun Items.rem(parts: Int): Items = Items(count % parts)

    operator fun Money.plus(that: Money): Money = Money(cents + that.cents)
    operator fun Money.minus(that: Money): Money = Money(cents - that.cents)
    operator fun Money.times(factor: Long): Money = Money(cents * factor)
    operator fun Money.div(parts: Long): Money = Money(cents / parts)
    operator fun Money.rem(parts: Long): Money = Money(cents % parts)

    operator fun Money.plus(tip: Int): Money = Money(cents + tip)
}

/**
 * Calls to the operators of [MoneyOperators], through `with` and through an import of the
 * object's member. Mutating them used to drop the right operand ("No argument for parameter")
 * and, with the operand kept, would have called the [Items] overload.
 */
@MutationTarget
class MemberExtensionOperatorTarget {

    val evaluated = mutableListOf<Long>()

    fun reset() {
        evaluated.clear()
    }

    @SuppressMutations
    private fun next(cents: Long): Money {
        evaluated.add(cents)
        return Money(cents)
    }

    /** `+` through `with`, mutated to `-`, over operands with a visible side effect. */
    fun total(a: Long, b: Long): Money = with(MoneyOperators) { next(a) + next(b) }

    /** `-` through the import, mutated to `+`. */
    fun difference(a: Money, b: Money): Money = a - b

    /** `*`, mutated to `/`. */
    fun scaled(amount: Money, factor: Long): Money = with(MoneyOperators) { amount * factor }

    /** `/`, mutated to `*`. */
    fun share(amount: Money, parts: Long): Money = with(MoneyOperators) { amount / parts }

    /** `%`, mutated to `/`. */
    fun remainder(amount: Money, parts: Long): Money = with(MoneyOperators) { amount % parts }

    /** `+` with no `minus` of the same signature: not mutated, since no other one would compile. */
    fun tipped(amount: Money, tip: Int): Money = with(MoneyOperators) { amount + tip }
}
