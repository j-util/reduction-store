package io.github.jutil.reductionstore;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Defines one reduction from input values to an owned state value.
 *
 * <p>A generated store creates one reduction implementation instance and one
 * state per reduction. It obtains the initial state by calling
 * {@code supplier().get()} once during store construction. For each value
 * added to the store, it evaluates
 * {@code reducer().apply(currentState, value)} and retains the returned value
 * as the new state.
 *
 * <p>The supplied state and the state returned by the reducer may be
 * {@code null}. Input values, including {@code null}, are passed to the
 * reducer unchanged. Implementations may mutate and return the current state.
 * A supplier or reducer method must not itself return {@code null}.
 *
 * @param <P> input value type
 * @param <R> reduction state type
 */
public interface Reduction<P, R> {

    /**
     * Returns the factory for this reduction's initial state.
     *
     * <p>The generated store calls this method once and invokes the returned
     * supplier once during store construction.
     *
     * @return a non-{@code null} initial-state supplier
     */
    Supplier<R> supplier();

    /**
     * Returns the function that advances this reduction's state.
     *
     * <p>The generated store calls this method once for each input value. The
     * function receives the current state and the input unchanged, and its
     * return value becomes the current state.
     *
     * @return a non-{@code null} reduction function
     */
    BiFunction<R, P, R> reducer();
}
