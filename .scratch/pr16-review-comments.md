# PR #16 Review Comments — Proposed Fixes

Upstream PR: https://github.com/anschnapp/mutflow/pull/16
Fork: https://github.com/trancee/mutflow-exception-swap (branch: `feature/exception-type-swap`)

Five review comments from `anschnapp`. Each mapped to a concrete fix below.

---

## Comment 1 — `ExceptionTypeSwapOperator.kt:42` (portability)

**Link**: `java.lang.XXX` is not portable for Kotlin Native. Prefer `kotlin.*` variants — they get typealiased on JVM to the java.lang variants.

**Proposed fix**: Replace all `java.lang.*` keys/values in `EXCEPTION_SWAPS` with `kotlin.*`:

```kotlin
internal val EXCEPTION_SWAPS: Map<String, String> = mapOf(
    "kotlin.IllegalArgumentException" to "kotlin.IllegalStateException",
    "kotlin.IllegalStateException" to "kotlin.IllegalArgumentException",
    "kotlin.NullPointerException" to "kotlin.IllegalArgumentException",
    "kotlin.IndexOutOfBoundsException" to "kotlin.IllegalStateException",
    "kotlin.UnsupportedOperationException" to "kotlin.IllegalStateException",
    "kotlin.ClassCastException" to "kotlin.IllegalArgumentException",
    "kotlin.NumberFormatException" to "kotlin.IllegalArgumentException",
    "kotlin.ArithmeticException" to "kotlin.IllegalStateException",
    "kotlin.NoSuchElementException" to "kotlin.IllegalStateException",
)
```

**Notes**:
- `ArrayIndexOutOfBoundsException` entries removed entirely (no `kotlin.*` typealias exists — see Comment 2)
- `RuntimeException` entry removed (see Comment 5)
- All other `java.lang.*` have direct `kotlin.*` typealiases

---

## Comment 2 — `ExceptionTypeSwapOperator.kt:42` (heads up — widen `matches()`)

**Link**: `matches()` currently only gets `IrConstructorCall`, so FQName string comparison won't work for `kotlin.*` (on JVM the alias resolves to `java.lang`). Need to pass `IrPluginContext` through to compare `IrClassSymbol`s. Same change as `variants()` already gets.

**Proposed fix**: Two-part change:

### Part A: Widen `ConstructorMutationOperator.matches()` signature

```kotlin
// Before
fun matches(call: IrConstructorCall): Boolean

// After
fun matches(call: IrConstructorCall, context: MutationContext): Boolean
```

Since `ExceptionTypeSwapOperator` is the only `ConstructorMutationOperator` implementation, no other classes are affected.

### Part B: Update `matches()` to use symbol comparison

```kotlin
override fun matches(call: IrConstructorCall, context: MutationContext): Boolean {
    val constructedType = call.symbol.owner.returnType
    val classSymbol = constructedType.classOrNull ?: return false
    val currentFqName = classSymbol.owner.fqNameWhenAvailable?.asString()
    val swapTarget = EXCEPTION_SWAPS[currentFqName] ?: return false
    val swapTargetClassId = ClassId.topLevel(FqName(swapTarget))
    val swapTargetSymbol = context.pluginContext.referenceClass(swapTargetClassId) ?: return false
    // Compare by symbol identity, not by FQName string
    return classSymbol == swapTargetSymbol || classSymbol.owner == swapTargetSymbol.owner
}
```

Wait — this approach compares the source class's symbol against the target class's symbol. That's wrong — we want to check if the source class has a swap entry, regardless of whether the symbols match. The correct approach is:

```kotlin
override fun matches(call: IrConstructorCall, context: MutationContext): Boolean {
    val constructedType = call.symbol.owner.returnType
    val classSymbol = constructedType.classOrNull ?: return false
    // Use FqName from kotlin.* side, resolve through pluginContext to get
    // the actual IrClassSymbol (handles JVM typealias resolution)
    val currentFqName = classSymbol.owner.fqNameWhenAvailable?.asString()
    val hasSwap = EXCEPTION_SWAPS.containsKey(currentFqName)
    if (!hasSwap) return false
    // Verify the swap target is resolvable (not a dead entry like java.util.NoSuchElementException)
    val swapTarget = EXCEPTION_SWAPS[currentFqName]!!
    val swapClassId = ClassId.topLevel(FqName(swapTarget))
    val swapSymbol = context.pluginContext.referenceClass(swapClassId) ?: return false
    return true
}
```

Actually, re-reading the reviewer's comment more carefully: "side benefit of the switch: java.lang.NoSuchElementException doesn't exist (it's java.util), so that entry is dead today and kotlin.NoSuchElementException revives it."

The reviewer is saying: by switching to `kotlin.NoSuchElementException`, the entry becomes resolvable. In `matches()`, if we try to `referenceClass` for `kotlin.NoSuchElementException`, it should succeed (because the typealias resolves). If we use string comparison of `fqNameWhenAvailable`, on JVM the FQName of `kotlin.NoSuchElementException` resolves to `java.lang.NoSuchElementException` (or `java.util.NoSuchElementException`), which won't match the `kotlin.NoSuchElementException` key in the map.

So the symbol-based approach fixes both:
1. Portability (kotlin.* FQNs work on all platforms)
2. Correct matching (resolves through pluginContext, handling typealias resolution)

And `variants()` already receives `MutationContext`, so it can use `referenceClass` to resolve the swap target.

### Part C: Update `transformConstructorCallWithOperators` to pass context

The call site at line 539 needs updating:
```kotlin
// Current (no context available at this point)
if (!operator.matches(original)) { ... }

// The MutationContext is created at line 568-569 in transformConstructorCallWithOperator
// We need to create it earlier, or restructure to pass pluginContext through
```

**Simplest fix**: Create the `MutationContext` before the `matches()` check:
```kotlin
private fun transformConstructorCallWithOperators(
    original: IrConstructorCall,
    containingFunction: IrSimpleFunction,
    remainingOperators: List<ConstructorMutationOperator>
): IrExpression {
    if (remainingOperators.isEmpty()) {
        return original
    }

    val operator = remainingOperators.first()
    val rest = remainingOperators.drop(1)

    val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
    val context = MutationContext(pluginContext, builder, containingFunction)

    if (!operator.matches(original, context)) {
        return transformConstructorCallWithOperators(original, containingFunction, rest)
    }

    return transformConstructorCallWithOperator(original, containingFunction, operator, rest, context)
}
```

And update `transformConstructorCallWithOperator` to accept the pre-built context instead of creating it.

---

## Comment 3 — `MutflowIrTransformer.kt:241` (use `visitThrow` instead of `visitConstructorCall`)

**Link**: The mutation won't work correctly if the exception is assigned to a variable before being thrown:
```kotlin
val e = IllegalStateException()
throw e
```
The injection changes `e`'s type, not the throw. Suggests `visitThrow` instead — "for throw all types of Throwable are allowed."

**Proposed fix**: Replace `visitConstructorCall` override (line 241) with `visitThrow`:

```kotlin
// Before
override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
    val transformed = super.visitConstructorCall(expression) as IrConstructorCall
    if (!isInMutationTarget || isInSuppressedScope) return transformed
    if (isLineSuppressedByComment(transformed.startOffset)) return transformed
    val fn = currentFunction ?: return transformed
    return transformConstructorCallWithOperators(transformed, fn, constructorOperators)
}

// After
override fun visitThrow(expression: IrThrow): IrExpression {
    val transformed = super.visitThrow(expression)
    if (!isInMutationTarget || isInSuppressedScope) return transformed
    if (isLineSuppressedByComment(transformed.startOffset)) return transformed

    val throwExpr = transformed.valueOrNull ?: return transformed
    val constructorCall = throwExpr as? IrConstructorCall ?: return transformed
    val fn = currentFunction ?: return transformed
    return transformConstructorCallWithOperators(constructorCall, fn, constructorOperators)
}
```

This only mutates constructor calls that are directly thrown — `IllegalArgumentException()` in `throw IllegalArgumentException()` — not ones assigned to variables. It needs:
- Import: `org.jetbrains.kotlin.ir.expressions.IrThrow`
- `IrThrow.valueOrNull` returns the thrown expression (returns `IrExpression?`)

**Tradeoff**: This means `ExceptionTypeSwapOperator` only applies to direct `throw NewException()` sites, not `val e = NewException(); throw e`. The latter case would need a separate operator (e.g., an IR value-replacement operator). The reviewer seems to accept this tradeoff.

---

## Comment 4 — `ExceptionTypeSwapOperator.kt:114` (IrType == too structural)

**Link**: `IrType ==` is structural — nullability and type attributes must match exactly. `String` and `String?` won't match. Better to compare `classifierOrNull`:

```kotlin
targetParamTypes.zip(sourceParamTypes).all { (target, source) ->
    target.classifierOrNull == source.classifierOrNull
}
```

**Proposed fix**: Replace line 114:
```kotlin
// Before
targetParamTypes.zip(sourceParamTypes).all { (target, source) -> target == source }

// After
targetParamTypes.zip(sourceParamTypes).all { (target, source) ->
    target.classifierOrNull == source.classifierOrNull
}
```

Need import: `org.jetbrains.kotlin.ir.types.classifierOrNull`

---

## Comment 5 — `ExceptionTypeSwapOperator.kt:52` (RuntimeException pair is invalid)

**Link**: `RuntimeException` to `IllegalArgumentException` doesn't make sense as a pair — `RuntimeException` is not a subtype of itself, and all other exceptions in the map ARE subtypes of `RuntimeException`. The pair is meaningless.

**Proposed fix**: Remove the `RuntimeException` entry from `EXCEPTION_SWAPS` (already covered by Comment 1's revised map).

---

## Summary of files to change

| File | Changes |
|---|---|
| `ExceptionTypeSwapOperator.kt` | Comments 1, 2, 4, 5 — `kotlin.*` FQNs, `matches()` takes `MutationContext`, `classifierOrNull` comparison, remove `RuntimeException` + `ArrayIndexOutOfBoundsException` entries |
| `ConstructorMutationOperator.kt` | Comment 2 — widen `matches()` signature to accept `MutationContext` |
| `MutflowIrTransformer.kt` | Comments 2, 3 — pass context to `matches()`, replace `visitConstructorCall` with `visitThrow` |
