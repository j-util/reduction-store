package io.github.jutil.reductionstore;

/**
 * Advances a {@code long} reduction state with one input value.
 *
 * @param <P> input value type
 */
@FunctionalInterface
public interface LongReducer<P> {

    /**
     * Returns the state produced by applying {@code value} to {@code state}.
     *
     * @param state current reduction state
     * @param value input value, possibly {@code null}
     * @return next reduction state
     */
    long apply(long state, P value);
}
