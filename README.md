# Reduction Store

[![Maven Central](https://img.shields.io/maven-central/v/io.github.j-util/reduction-store.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.j-util/reduction-store)

Reduction Store generates a strongly typed in-memory reduction store from
ordinary classes that implement `Reduction<P, R>`, `IntReduction<P>`,
`LongReduction<P>`, or `DoubleReduction<P>`. Client reduction classes do not
use annotations. The runtime contract and compiler processor are separate,
dependency-free Java 8 artifacts.

The current version is `1.0.0`. Both artifacts are available from Maven Central:

- [`io.github.j-util:reduction-store`](https://central.sonatype.com/artifact/io.github.j-util/reduction-store)
- [`io.github.j-util:reduction-store-processor`](https://central.sonatype.com/artifact/io.github.j-util/reduction-store-processor)

## Client configuration

The runtime contract belongs on the compile and runtime class paths. The
processor belongs only on the annotation-processor path:

```xml
<properties>
    <maven.compiler.release>8</maven.compiler.release>
    <reduction-store.version>1.0.0</reduction-store.version>
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
for javac on JDK 23 and later.

For contributors, `./mvnw verify` also builds two separate isolated Maven
consumers under `reduction-store-processor/src/it`. One verifies ordinary
annotation-free discovery and one uses an explicit definition against
separately compiled model and reduction JARs. Both exercise their generated
stores at runtime, independently of the processor test harness.

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

compilation generates public final `example.RowReductionStore` with this API:

```java
public RowReductionStore();
public void add(Row value);
public long count();
public Map<String, Integer> countByCountry();
```

## Reducers from another module or JAR

Most clients need no definition annotation. Automatic discovery covers
reductions participating in the current full compilation, but it cannot infer
an arbitrary reducer set from dependency JARs. When reducers come from other
modules or JARs, place `ReductionStoreDefinition` in the downstream composition
or consumer module and list the reducers that store should use:

```java
@ReductionStoreDefinition(
        input = Row.class,
        reductions = {
                CountReduction.class,
                TotalReduction.class
        }
)
interface RowStoreDefinition {
}
```

This generates `RowReductionStore` in `RowStoreDefinition`'s package, even when
`Row` belongs to another package. The listed reductions are authoritative for
that store; other reductions present in dependencies are not added. For
cross-module use, every listed input and reduction type must already be visible
to the current compilation, either as a source type or from a compiled
dependency. The input type, reduction classes and their no-argument
constructors, and object-state types must also be accessible from the
definition package. In practice, types supplied by another module normally
need to be public, as do the required constructors and enclosing types. Class
values that another processor would generate in a later round are not
supported and produce a compilation error.

If an IDE partial compilation leaves generated code stale, run:

```shell
./mvnw clean compile
```

## Mapping input

Mapping belongs to the ingestion pipeline. If only one reduction needs a
different view of an input, fuse that mapping into the reduction's reducer. If
several reductions share a mapped type, map each `SourceRow` to `Row` once
before calling `RowReductionStore.add(row)`; the store then passes that same
`Row` unchanged to every reducer. Reduction Store deliberately provides no
mapper API and does not own input conversion.

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
reducer objects themselves must be non-null; otherwise store construction fails
with a `NullPointerException` naming the implementation and offending method.
If a constructor, supplier, or reducer fails, the exception propagates. During
a failing `add`, earlier reductions remain applied and later reductions are not
called. Object and primitive reductions share one qualified
implementation-class-name ordering. Stores are not thread-safe.

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

- For automatic discovery, `P` must be a top-level, non-generic type compiled
  in the same javac invocation. An explicit `ReductionStoreDefinition` may
  instead reference accessible listed types already visible to the current
  compilation as source types or compiled dependencies.
- A reduction implementation must be a concrete, non-generic class with an
  accessible no-argument constructor that declares no checked exceptions. A
  member implementation must be static.
- The implementation and every type used by `R` must be accessible from `P`'s
  package.
- Accessor collisions across all reduction kinds, Java keyword names, `Object`
  method conflicts, raw reduction implementations, and conflicting generated
  type names are compiler errors.
- Automatic discovery covers source roots in the current full compilation. It
  does not scan classpath JARs or reductions generated by another processor in
  a later round. Explicit definitions use only their listed, already-visible
  class values; they do not scan dependencies, broaden discovery, or support
  class values generated in a later processing round. Incremental or IDE
  partial compilation cannot reliably reconstruct an automatic aggregate; use
  a clean/full compile after adding, removing, moving, or renaming a reduction.

There is no runtime reflection, registry, service discovery, or public untyped
state map.
