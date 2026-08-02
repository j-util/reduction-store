package io.github.jutil.reductionstore;

/**
 * Advances a {@code double} reduction state with one input value.
 *
 * @param <P> input value type
 */
@FunctionalInterface
public interface DoubleReducer<P> {

    /**
     * Returns the state produced by applying {@code value} to {@code state}.
     *
     * @param state current reduction state
     * @param value input value, possibly {@code null}
     * @return next reduction state
     */
    double apply(double state, P value);
}
