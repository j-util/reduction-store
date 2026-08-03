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
    void rejectsJavaKeywordAccessor(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                javaKeywordAccessorSource(),
                "Reduction class name produces an invalid accessor: class()");
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

    @Test
    void rejectsGenericInputType(@TempDir Path temporaryDirectory)
            throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                genericInputSource(),
                "Reduction input type must be non-generic");
    }

    @Test
    void rejectsInputOutsideCurrentCompilation(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Count",
                classpathInputSource(),
                "Reduction input type must be compiled in the same full "
                        + "javac invocation");
    }

    @Test
    void rejectsNonStaticMemberImplementation(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                nonStaticMemberSource(),
                "Member reduction implementation classes must be static");
    }

    @Test
    void rejectsCheckedNoArgConstructor(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                checkedConstructorSource(),
                "Reduction no-argument constructor must not declare checked "
                        + "exceptions");
    }

    @Test
    void rejectsImplementationInaccessibleFromGeneratedPackage(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(
                        "model.Row", modelRowSource(),
                        "implementation.Count",
                        inaccessibleImplementationSource()));

        assertRejected(
                compilation,
                "Reduction implementation is not accessible from generated "
                        + "package model");
    }

    @Test
    void rejectsStateTypeInaccessibleFromGeneratedPackage(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(
                        "model.Row", modelRowSource(),
                        "implementation.Count", inaccessibleStateSource()));

        assertRejected(
                compilation,
                "Reduction state type is not accessible from generated "
                        + "package model");
    }

    @Test
    void rejectsAccessorThatConflictsWithObject(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "invalid.Row",
                objectMethodConflictSource(),
                "Reduction accessor toString() conflicts with "
                        + "java.lang.Object");
    }

    @Test
    void rejectsEmptyExplicitReductionList(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "explicit.Definition",
                emptyExplicitDefinitionSource(),
                "ReductionStoreDefinition reductions() must not be empty");
    }

    @Test
    void rejectsDuplicateExplicitReductions(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "explicit.Definition",
                duplicateExplicitReductionSource(),
                "ReductionStoreDefinition contains duplicate reduction");
    }

    @Test
    void rejectsExplicitReductionWithMismatchedInput(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "explicit.Definition",
                mismatchedExplicitInputSource(),
                "does not exactly match definition input explicit.Row");
    }

    @Test
    void rejectsExplicitNonReductionClass(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "explicit.Definition",
                explicitNonReductionSource(),
                "must implement exactly one supported reduction contract");
    }

    @Test
    void rejectsExplicitImplementationInaccessibleFromDefinitionPackage(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(
                        "model.Row", modelRowSource(),
                        "implementation.Count",
                        inaccessibleImplementationSource(),
                        "composition.Definition",
                        inaccessibleExplicitImplementationDefinition()));

        assertRejected(
                compilation,
                "implementation.Count is not public in implementation");
    }

    @Test
    void rejectsExplicitStateInaccessibleFromDefinitionPackage(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(
                        "model.Row", modelRowSource(),
                        "implementation.Count", inaccessibleStateSource(),
                        "composition.Definition",
                        inaccessibleExplicitStateDefinition()));

        assertRejected(
                compilation,
                "Reduction state type is not accessible from generated "
                        + "package composition");
    }

    @Test
    void rejectsExplicitGeneratedNameCollision(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "collision.Definition",
                explicitNameCollisionSource(),
                "Generated reduction store name conflicts with existing type "
                        + "collision.RowReductionStore");
    }

    @Test
    void rejectsDuplicateExplicitGeneratedTargets(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(
                        "first.Row", firstDuplicateTargetInput(),
                        "first.Count", firstDuplicateTargetReduction(),
                        "second.Row", secondDuplicateTargetInput(),
                        "second.Count", secondDuplicateTargetReduction(),
                        "composition.Definitions",
                        duplicateTargetDefinitions()));

        assertRejected(
                compilation,
                "Multiple ReductionStoreDefinition declarations target "
                        + "generated class composition.RowReductionStore");
    }

    @Test
    void reportsExplicitTypesStillUnresolvedAfterProcessing(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "unresolved.Definition",
                unresolvedExplicitDefinitionSource(),
                "ReductionStoreDefinition class values remain unresolved "
                        + "after all processing rounds");
    }

    @Test
    void rejectsInvalidExplicitDefinitionAnchor(
            @TempDir Path temporaryDirectory) throws Exception {
        assertRejected(
                temporaryDirectory,
                "explicit.InvalidDefinition",
                invalidDefinitionAnchorSource(),
                "must annotate a top-level, non-generic interface");
    }

    private static void assertRejected(
            Path temporaryDirectory,
            String sourceName,
            String source,
            String expectedDiagnostic) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources(sourceName, source));

        assertRejected(compilation, expectedDiagnostic);
    }

    private static void assertRejected(
            CompilerTestSupport.Compilation compilation,
            String expectedDiagnostic) throws Exception {

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

    private static String javaKeywordAccessorSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class Class implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
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

    private static String genericInputSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row<T> {}",
                "final class Count implements LongReduction<Row<String>> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row<String>> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String classpathInputSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Count implements LongReduction<String> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<String> reducer() {",
                "    return (state, value) -> state + 1L;",
                "  }",
                "}");
    }

    private static String nonStaticMemberSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class Owner {",
                "  final class Count implements LongReduction<Row> {",
                "    public LongSupplier supplier() { return () -> 0L; }",
                "    public LongReducer<Row> reducer() {",
                "      return (state, row) -> state + 1L;",
                "    }",
                "  }",
                "}");
    }

    private static String checkedConstructorSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.io.IOException;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class Count implements LongReduction<Row> {",
                "  Count() throws IOException {}",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String modelRowSource() {
        return lines(
                "package model;",
                "public final class Row {}");
    }

    private static String inaccessibleImplementationSource() {
        return lines(
                "package implementation;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "import model.Row;",
                "final class Count implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String inaccessibleStateSource() {
        return lines(
                "package implementation;",
                "import io.github.jutil.reductionstore.Reduction;",
                "import java.util.function.BiFunction;",
                "import java.util.function.Supplier;",
                "import model.Row;",
                "final class HiddenState {}",
                "public final class Count",
                "    implements Reduction<Row, HiddenState> {",
                "  public Supplier<HiddenState> supplier() {",
                "    return HiddenState::new;",
                "  }",
                "  public BiFunction<HiddenState, Row, HiddenState> reducer() {",
                "    return (state, row) -> state;",
                "  }",
                "}");
    }

    private static String objectMethodConflictSource() {
        return lines(
                "package invalid;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class ToString implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String emptyExplicitDefinitionSource() {
        return lines(
                "package explicit;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "final class Row {}",
                "@ReductionStoreDefinition(input = Row.class, reductions = {})",
                "interface Definition {}");
    }

    private static String duplicateExplicitReductionSource() {
        return lines(
                "package explicit;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.LongSupplier;",
                "final class Row {}",
                "final class Count implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}",
                "@ReductionStoreDefinition(",
                "    input = Row.class, reductions = {Count.class, Count.class})",
                "interface Definition {}");
    }

    private static String mismatchedExplicitInputSource() {
        return lines(
                "package explicit;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.LongSupplier;",
                "final class Row {}",
                "final class Other {}",
                "final class Count implements LongReduction<Other> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Other> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}",
                "@ReductionStoreDefinition(input = Row.class,",
                "    reductions = Count.class)",
                "interface Definition {}");
    }

    private static String explicitNonReductionSource() {
        return lines(
                "package explicit;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "final class Row {}",
                "final class NotAReduction {}",
                "@ReductionStoreDefinition(input = Row.class,",
                "    reductions = NotAReduction.class)",
                "interface Definition {}");
    }

    private static String inaccessibleExplicitImplementationDefinition() {
        return lines(
                "package composition;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "import model.Row;",
                "@ReductionStoreDefinition(input = Row.class,",
                "    reductions = implementation.Count.class)",
                "interface Definition {}");
    }

    private static String inaccessibleExplicitStateDefinition() {
        return lines(
                "package composition;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "import implementation.Count;",
                "import model.Row;",
                "@ReductionStoreDefinition(input = Row.class,",
                "    reductions = Count.class)",
                "interface Definition {}");
    }

    private static String explicitNameCollisionSource() {
        return lines(
                "package collision;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.LongSupplier;",
                "final class Row {}",
                "final class Count implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}",
                "final class RowReductionStore {}",
                "@ReductionStoreDefinition(input = Row.class,",
                "    reductions = Count.class)",
                "interface Definition {}");
    }

    private static String firstDuplicateTargetInput() {
        return lines("package first;", "public final class Row {}");
    }

    private static String secondDuplicateTargetInput() {
        return lines("package second;", "public final class Row {}");
    }

    private static String firstDuplicateTargetReduction() {
        return publicCountSource("first");
    }

    private static String secondDuplicateTargetReduction() {
        return publicCountSource("second");
    }

    private static String publicCountSource(String packageName) {
        return lines(
                "package " + packageName + ";",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.LongSupplier;",
                "public final class Count implements LongReduction<Row> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> state + 1L;",
                "  }",
                "}");
    }

    private static String duplicateTargetDefinitions() {
        return lines(
                "package composition;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "@ReductionStoreDefinition(input = first.Row.class,",
                "    reductions = first.Count.class)",
                "interface FirstDefinition {}",
                "@ReductionStoreDefinition(input = second.Row.class,",
                "    reductions = second.Count.class)",
                "interface SecondDefinition {}");
    }

    private static String unresolvedExplicitDefinitionSource() {
        return lines(
                "package unresolved;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "@ReductionStoreDefinition(input = MissingRow.class,",
                "    reductions = MissingReduction.class)",
                "interface Definition {}");
    }

    private static String invalidDefinitionAnchorSource() {
        return lines(
                "package explicit;",
                "import io.github.jutil.reductionstore.ReductionStoreDefinition;",
                "@ReductionStoreDefinition(input = String.class,",
                "    reductions = String.class)",
                "public final class InvalidDefinition<T> {}");
    }
}
