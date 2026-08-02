package io.github.jutil.reductionstore;

import java.util.function.LongSupplier;

/**
 * Defines one reduction from input values to a {@code long} state.
 *
 * <p>A generated store obtains the supplier and reducer once during
 * construction, invokes the supplier once, and retains the primitive state
 * and reducer. Each added value is passed unchanged to the reducer once.
 *
 * @param <P> input value type
 */
public interface LongReduction<P> {

    /**
     * Returns the supplier for this reduction's initial state.
     *
     * @return a non-{@code null} initial-state supplier
     */
    LongSupplier supplier();

    /**
     * Returns the function that advances this reduction's state.
     *
     * @return a non-{@code null} reduction function
     */
    LongReducer<P> reducer();
}
