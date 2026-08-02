package io.github.jutil.reductionstore;

import java.util.function.DoubleSupplier;

/**
 * Defines one reduction from input values to a {@code double} state.
 *
 * <p>A generated store obtains the supplier and reducer once during
 * construction, invokes the supplier once, and retains the primitive state
 * and reducer. Each added value is passed unchanged to the reducer once.
 *
 * @param <P> input value type
 */
public interface DoubleReduction<P> {

    /**
     * Returns the supplier for this reduction's initial state.
     *
     * @return a non-{@code null} initial-state supplier
     */
    DoubleSupplier supplier();

    /**
     * Returns the function that advances this reduction's state.
     *
     * @return a non-{@code null} reduction function
     */
    DoubleReducer<P> reducer();
}
