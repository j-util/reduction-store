package io.github.jutil.reductionstore.processor;

import static io.github.jutil.reductionstore.processor.CompilerTestSupport.compile;
import static io.github.jutil.reductionstore.processor.CompilerTestSupport.lines;
import static io.github.jutil.reductionstore.processor.CompilerTestSupport.sources;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReductionProcessorValidationTest {

    @Test
    void rejectsRawObjectReduction(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                rawObjectReductionSource(),
                "Reduction must not be implemented as a raw type");
    }

    @Test
    void rejectsRawPrimitiveReduction(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                rawPrimitiveReductionSource(),
                "IntReduction must not be implemented as a raw type");
    }

    @Test
    void rejectsAccessorCollisionBetweenObjectReductions(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                objectAccessorCollisionSource(),
                "Reduction accessor collision");
    }

    @Test
    void rejectsAccessorCollisionAcrossReductionKinds(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                crossKindAccessorCollisionSource(),
                "Reduction accessor collision");
    }

    @Test
    void rejectsInaccessibleNoArgConstructor(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                inaccessibleConstructorSource(),
                "must have a no-argument constructor accessible");
    }

    @Test
    void rejectsGenericReductionImplementation(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                genericReductionSource(),
                "Reduction implementation classes must be non-generic");
    }

    @Test
    void rejectsNestedInputType(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Outer",
                nestedInputSource(),
                "Reduction input type must be top-level");
    }

    private static void assertRejected(
            Path temporaryDirectory,
            String sourceName,
            String source,
            String expectedDiagnostic) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(sourceName, source));

        assertFalse(compilation.succeeded(),
                "Compilation unexpectedly succeeded");
        assertTrue(
                compilation.diagnostics().contains(expectedDiagnostic),
                "Expected diagnostic containing '" + expectedDiagnostic
                        + "' but got:\n" + compilation.diagnostics());
    }

    private static String rawObjectReductionSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.Reduction;",
                "import java.util.function.BiFunction;",
                "import java.util.function.Supplier;",
                "public final class Row {}",
                "final class RawObject implements Reduction {",
                "  public Supplier supplier() { return () -> \"\"; }",
                "  public BiFunction reducer() {",
                "    return (state, row) -> state;",
                "  }",
                "}");
    }

    private static String rawPrimitiveReductionSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.IntReducer;",
                "import io.github.jutil.reductionstore.IntReduction;",
                "import java.util.function.IntSupplier;",
                "public final class Row {}",
                "final class RawInt implements IntReduction {",
                "  public IntSupplier supplier() { return () -> 0; }",
                "  public IntReducer reducer() {",
                "    return (state, row) -> state;",
                "  }",
                "}");
    }

    private static String objectAccessorCollisionSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.Reduction;",
                "import java.util.function.BiFunction;",
                "import java.util.function.Supplier;",
                "public final class Row {}",
                "final class First {",
                "  static final class Duplicate implements Reduction<Row, String> {",
                "    public Supplier<String> supplier() { return () -> \"\"; }",
                "    public BiFunction<String, Row, String> reducer() {",
                "      return (state, row) -> state;",
                "    }",
                "  }",
                "}",
                "final class Second {",
                "  static final class Duplicate implements Reduction<Row, String> {",
                "    public Supplier<String> supplier() { return () -> \"\"; }",
                "    public BiFunction<String, Row, String> reducer() {",
                "      return (state, row) -> state;",
                "    }",
                "  }",
                "}");
    }

    private static String crossKindAccessorCollisionSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.*;",
                "public final class Row {}",
                "final class ObjectOwner {",
                "  static final class Duplicate implements Reduction<Row, String> {",
                "    public Supplier<String> supplier() { return () -> \"\"; }",
                "    public BiFunction<String, Row, String> reducer() {",
                "      return (state, row) -> state;",
                "    }",
                "  }",
                "}",
                "final class PrimitiveOwner {",
                "  static final class Duplicate implements IntReduction<Row> {",
                "    public IntSupplier supplier() { return () -> 0; }",
                "    public IntReducer<Row> reducer() {",
                "      return (state, row) -> state;",
                "    }",
                "  }",
                "}");
    }

    private static String inaccessibleConstructorSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class PrivateCount implements LongReduction<Row> {",
                "  private PrivateCount() {}",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String genericReductionSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class GenericCount<T> implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String nestedInputSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Outer {",
                "  static final class Row {}",
                "}",
                "final class Count implements LongReduction<Outer.Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Outer.Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }
}
