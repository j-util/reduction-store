package io.github.jutil.reductionstore;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly selects the input type and reductions for one generated store.
 *
 * <p>Most clients do not need this annotation: reductions compiled together
 * with their input type are discovered automatically. Use a definition in a
 * downstream composition module when the selected reductions are supplied by
 * other modules or JARs. The generated store is placed in the annotated
 * interface's package and is named from the input type as
 * {@code <InputSimpleName>ReductionStore}.
 *
 * <p>The listed reductions are authoritative for this store. Their annotation
 * order does not affect execution order; generated stores continue to execute
 * reductions in qualified implementation-name order. The input, reduction
 * implementations, constructors, and state types must be accessible from the
 * annotated interface's package.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ReductionStoreDefinition {

    /**
     * Returns the input type accepted by the generated store.
     *
     * @return the top-level, non-generic input type
     */
    Class<?> input();

    /**
     * Returns the authoritative reduction implementations for the generated
     * store.
     *
     * @return one or more distinct, concrete reduction implementation classes
     */
    Class<?>[] reductions();
}
