package io.github.jutil.reductionstore;

/**
 * Advances an {@code int} reduction state with one input value.
 *
 * @param <P> input value type
 */
@FunctionalInterface
public interface IntReducer<P> {

    /**
     * Returns the state produced by applying {@code value} to {@code state}.
     *
     * @param state current reduction state
     * @param value input value, possibly {@code null}
     * @return next reduction state
     */
    int apply(int state, P value);
}
