# Reduction Store

Reduction Store generates a strongly typed in-memory reduction store from
ordinary classes that implement `Reduction<P, R>`, `IntReduction<P>`,
`LongReduction<P>`, or `DoubleReduction<P>`. Client reduction classes do not
use annotations. The runtime contract and compiler processor are separate,
dependency-free Java 8 artifacts.

This first version is unreleased and uses version `0.1.0-SNAPSHOT`.

## Client configuration

The runtime contract belongs on the compile and runtime class paths. The
processor belongs only on the annotation-processor path:

```xml
<properties>
    <maven.compiler.release>8</maven.compiler.release>
    <reduction-store.version>0.1.0-SNAPSHOT</reduction-store.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.j-util</groupId>
        <artifactId>reduction-store</artifactId>
        <version>${reduction-store.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.15.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.j-util</groupId>
                        <artifactId>reduction-store-processor</artifactId>
                        <version>${reduction-store.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Explicit processor-path configuration is required for predictable builds and
for javac on JDK 23 and later. Until the artifacts are published, run
`./mvnw install` in this repository before compiling a separate Maven client.

## Example

Given top-level types in package `example`:

```java
final class CountByCountry
        implements Reduction<Row, Map<String, Integer>> {

    @Override
    public Supplier<Map<String, Integer>> supplier() {
        return HashMap::new;
    }

    @Override
    public BiFunction<Map<String, Integer>, Row, Map<String, Integer>> reducer() {
        return (map, row) -> {
            map.merge(row.country(), 1, Integer::sum);
            return map;
        };
    }
}

final class Count implements LongReduction<Row> {

    @Override
    public LongSupplier supplier() {
        return () -> 0L;
    }

    @Override
    public LongReducer<Row> reducer() {
        return (count, row) -> count + 1L;
    }
}
```

and a `TotalAmount implements Reduction<Row, BigDecimal>`, compilation
generates public final `example.RowReductionStore` with this API:

```java
public RowReductionStore();
public void add(Row value);
public long count();
public Map<String, Integer> countByCountry();
public BigDecimal totalAmount();
```

`IntReduction`, `LongReduction`, and `DoubleReduction` specialize the state,
not the input value. Their generated state fields and accessor return types are
the corresponding primitives. Their retained reducers accept and return those
primitives directly, avoiding wrapper state and per-item boxing or unboxing of
the state introduced by the library.

During construction, the store creates one implementation instance per
reduction as a local variable. It obtains each supplier once, invokes that
supplier once to initialize the state, and obtains each reducer once. The
store retains the fully typed reducer function and its object or primitive
state, but no direct field reference to the reduction implementation. A
returned reducer or object state may still retain that implementation according
to the reduction's own behavior.

Every `add(value)` invokes each retained reducer exactly once as
`reducer.apply(currentState, value)` and assigns the returned value back to
that state. Object-state accessors return the current state reference without
copying it; primitive-state accessors return the current primitive value.

`null` inputs are passed through. An object-state supplier or reducer may
produce a `null` state; primitive states cannot be `null`. The supplier and
reducer objects themselves must be non-null. If a constructor, supplier, or
reducer fails, the exception propagates. During a failing `add`, earlier
reductions remain applied and later reductions are not called. Object and
primitive reductions share one qualified implementation-class-name ordering.
Stores are not thread-safe.

For `k` reductions, construction performs `O(k)` library work plus the
implementation constructors, supplier acquisition and invocation, and reducer
acquisition. Each `add` performs `O(k)` library work plus one invocation of
each retained reducer. Each state accessor is `O(1)`. Store fields contribute
one retained reducer reference per reduction, plus either one object-state
reference or one primitive state field, in addition to memory reachable from
the retained objects.

## Discovery and V1 limits

The processor is registered through
`META-INF/services/javax.annotation.processing.Processor` and declares support
for `*`. On the first processing round it recursively inspects javac root types,
resolves their generic supertypes, and selects concrete classes whose resolved
supertype is `Reduction<P, R>`, `IntReduction<P>`, `LongReduction<P>`, or
`DoubleReduction<P>`. This includes indirect and inherited implementations. It
groups every object and primitive reduction with the same `P` into one store in
`P`'s package. The store name is `<P simple name>ReductionStore`; an accessor
lowercases the first code point of the reduction implementation's simple name.

V1 deliberately has these compile-time boundaries:

- `P` must be a top-level, non-generic type compiled in the same javac
  invocation.
- A reduction implementation must be a concrete, non-generic class with an
  accessible no-argument constructor that declares no checked exceptions. A
  member implementation must be static.
- The implementation and every type used by `R` must be accessible from `P`'s
  package.
- Accessor collisions across all reduction kinds, Java keyword names, `Object`
  method conflicts, raw reduction implementations, and conflicting generated
  type names are compiler errors.
- Discovery covers source roots in the current full compilation. It does not
  scan classpath JARs or reductions generated by another processor in a later
  round. Incremental or IDE partial compilation cannot reliably reconstruct a
  complete aggregate; use a clean/full compile after adding, removing, moving,
  or renaming a reduction.

There is no runtime reflection, registry, service discovery, cross-library
integration, or public untyped state map.
