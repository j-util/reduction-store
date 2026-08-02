package io.github.jutil.reductionstore.processor;

import static io.github.jutil.reductionstore.processor.CompilerTestSupport.compile;
import static io.github.jutil.reductionstore.processor.CompilerTestSupport.lines;
import static io.github.jutil.reductionstore.processor.CompilerTestSupport.sources;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jutil.reductionstore.DoubleReducer;
import io.github.jutil.reductionstore.IntReducer;
import io.github.jutil.reductionstore.LongReducer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReductionProcessorGenerationTest {

    @Test
    void objectReductionGeneratesExactTypeAndEvolvesState(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources("example.Row", objectReductionSource()));

        assertTrue(compilation.succeeded(), compilation.diagnostics());
        assertEquals(1, compilation.generatedJavaFiles().size());
        try (URLClassLoader loader = compilation.newClassLoader()) {
            Class<?> rowType = loader.loadClass("example.Row");
            Class<?> storeType = loader.loadClass("example.RowReductionStore");
            Object store = storeType.getConstructor().newInstance();
            Method add = storeType.getMethod("add", rowType);
            Method accessor = storeType.getMethod("countByCountry");

            assertSame(Map.class, accessor.getReturnType());
            assertEquals(
                    "java.util.Map<java.lang.String, java.lang.Integer>",
                    accessor.getGenericReturnType().getTypeName());

            Object first = rowType.getConstructor(String.class)
                    .newInstance("AM");
            Object second = rowType.getConstructor(String.class)
                    .newInstance("US");
            Object third = rowType.getConstructor(String.class)
                    .newInstance("AM");
            add.invoke(store, first);
            add.invoke(store, second);
            add.invoke(store, third);

            Object state = accessor.invoke(store);
            assertTrue(state instanceof Map);
            Map<?, ?> counts = (Map<?, ?>) state;
            assertEquals(2, counts.get("AM"));
            assertEquals(1, counts.get("US"));
        }
    }

    @Test
    void mixedStoreUsesPrimitiveStateAndCachesConstructionWork(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources("mixed.Row", mixedReductionSource()));

        assertTrue(compilation.succeeded(), compilation.diagnostics());
        assertEquals(1, compilation.generatedJavaFiles().size());
        String generated = compilation.generatedSource(
                "mixed.RowReductionStore");
        assertPrimitiveSourceShape(generated);

        try (URLClassLoader loader = compilation.newClassLoader()) {
            Class<?> rowType = loader.loadClass("mixed.Row");
            Class<?> storeType = loader.loadClass("mixed.RowReductionStore");
            Object store = storeType.getConstructor().newInstance();

            assertSame(Map.class,
                    storeType.getMethod("countByCountry").getReturnType());
            assertSame(int.class, storeType.getMethod("flags").getReturnType());
            assertSame(long.class, storeType.getMethod("count").getReturnType());
            assertSame(double.class, storeType.getMethod("total").getReturnType());
            assertPrimitiveCompiledShape(storeType);
            assertLifecycleCounts(loader, 0);

            Method add = storeType.getMethod("add", rowType);
            add.invoke(store, rowType
                    .getConstructor(String.class, boolean.class, double.class)
                    .newInstance("AM", true, 1.25d));
            add.invoke(store, rowType
                    .getConstructor(String.class, boolean.class, double.class)
                    .newInstance("US", false, 2.0d));
            add.invoke(store, rowType
                    .getConstructor(String.class, boolean.class, double.class)
                    .newInstance("AM", true, 3.0d));

            Map<?, ?> countries = (Map<?, ?>) storeType
                    .getMethod("countByCountry").invoke(store);
            assertEquals(2, countries.get("AM"));
            assertEquals(1, countries.get("US"));
            assertEquals(4, storeType.getMethod("flags").invoke(store));
            assertEquals(13L, storeType.getMethod("count").invoke(store));
            assertEquals(
                    6.75d,
                    ((Number) storeType.getMethod("total").invoke(store))
                            .doubleValue(),
                    0.0d);
            assertLifecycleCounts(loader, 3);
        }
    }

    @Test
    void inheritedImplementationIsDiscoveredAndNamed(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources("indirect.Row", indirectReductionSource()));

        assertTrue(compilation.succeeded(), compilation.diagnostics());
        try (URLClassLoader loader = compilation.newClassLoader()) {
            Class<?> rowType = loader.loadClass("indirect.Row");
            Class<?> storeType = loader.loadClass(
                    "indirect.RowReductionStore");
            Object store = storeType.getConstructor().newInstance();
            Method add = storeType.getMethod("add", rowType);
            add.invoke(store, rowType.getConstructor(double.class)
                    .newInstance(2.25d));
            add.invoke(store, rowType.getConstructor(double.class)
                    .newInstance(1.75d));

            Method totalAmount = storeType.getMethod("totalAmount");
            assertSame(double.class, totalAmount.getReturnType());
            assertEquals(
                    5.0d,
                    ((Number) totalAmount.invoke(store)).doubleValue(),
                    0.0d);
        }
    }

    @Test
    void failingReducerKeepsEarlierProgressAndSkipsLaterReducers(
            @TempDir Path temporaryDirectory) throws Exception {
        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources("failure.Row", failureOrderingSource()));

        assertTrue(compilation.succeeded(), compilation.diagnostics());
        try (URLClassLoader loader = compilation.newClassLoader()) {
            Class<?> rowType = loader.loadClass("failure.Row");
            Class<?> storeType = loader.loadClass("failure.RowReductionStore");
            Object store = storeType.getConstructor().newInstance();
            Method add = storeType.getMethod("add", rowType);

            InvocationTargetException thrown = assertThrows(
                    InvocationTargetException.class,
                    () -> add.invoke(
                            store, rowType.getConstructor().newInstance()));
            assertEquals("deliberate failure", thrown.getCause().getMessage());
            assertEquals(1L, storeType.getMethod("aEarlier").invoke(store));
            assertEquals(0L, storeType.getMethod("mFailing").invoke(store));
            assertEquals(0L, storeType.getMethod("zLater").invoke(store));
            assertEquals(1, staticInt(loader, "failure.AEarlier", "invocations"));
            assertEquals(1, staticInt(loader, "failure.MFailing", "invocations"));
            assertEquals(0, staticInt(loader, "failure.ZLater", "invocations"));
        }
    }

    @Test
    void serviceDiscoveryProcessesACompletelyUnannotatedClient(
            @TempDir Path temporaryDirectory) throws Exception {
        assertProcessorServiceRegistration();
        String source = zeroAnnotationSource();
        assertFalse(source.contains("@"));

        CompilerTestSupport.Compilation compilation = compile(
                temporaryDirectory,
                sources("zero.Event", source));

        assertTrue(compilation.succeeded(), compilation.diagnostics());
        assertNotNull(compilation.generatedSource("zero.EventReductionStore"));
    }

    private static void assertPrimitiveSourceShape(String generated) {
        assertTrue(generated.matches(
                "(?s).*private\\s+int\\s+state\\d+;.*"), generated);
        assertTrue(generated.matches(
                "(?s).*private\\s+long\\s+state\\d+;.*"), generated);
        assertTrue(generated.matches(
                "(?s).*private\\s+double\\s+state\\d+;.*"), generated);
        assertTrue(generated.contains(
                "io.github.jutil.reductionstore.IntReducer<mixed.Row>"));
        assertTrue(generated.contains(
                "io.github.jutil.reductionstore.LongReducer<mixed.Row>"));
        assertTrue(generated.contains(
                "io.github.jutil.reductionstore.DoubleReducer<mixed.Row>"));
        assertTrue(generated.contains(".supplier().getAsInt()"));
        assertTrue(generated.contains(".supplier().getAsLong()"));
        assertTrue(generated.contains(".supplier().getAsDouble()"));
        assertFalse(generated.matches(
                "(?s).*private\\s+(?:java\\.lang\\.)?"
                        + "(?:Integer|Long|Double|Object)\\s+state\\d+;.*"),
                generated);
    }

    private static void assertPrimitiveCompiledShape(Class<?> storeType) {
        Map<Class<?>, Integer> stateTypes = new HashMap<Class<?>, Integer>();
        Map<Class<?>, Integer> reducerTypes = new HashMap<Class<?>, Integer>();
        for (Field field : storeType.getDeclaredFields()) {
            Map<Class<?>, Integer> counts = field.getName().startsWith("state")
                    ? stateTypes : reducerTypes;
            Integer previous = counts.get(field.getType());
            counts.put(field.getType(), previous == null ? 1 : previous + 1);
        }

        assertEquals(4, total(stateTypes));
        assertEquals(1, count(stateTypes, int.class));
        assertEquals(1, count(stateTypes, long.class));
        assertEquals(1, count(stateTypes, double.class));
        assertEquals(0, count(stateTypes, Integer.class));
        assertEquals(0, count(stateTypes, Long.class));
        assertEquals(0, count(stateTypes, Double.class));
        assertEquals(0, count(stateTypes, Object.class));

        assertEquals(4, total(reducerTypes));
        assertEquals(1, count(reducerTypes, IntReducer.class));
        assertEquals(1, count(reducerTypes, LongReducer.class));
        assertEquals(1, count(reducerTypes, DoubleReducer.class));
        assertEquals(1, count(reducerTypes, BiFunction.class));
        assertEquals(0, count(reducerTypes, Supplier.class));
    }

    private static int total(Map<Class<?>, Integer> counts) {
        int total = 0;
        for (Integer count : counts.values()) {
            total += count;
        }
        return total;
    }

    private static int count(
            Map<Class<?>, Integer> counts, Class<?> type) {
        Integer count = counts.get(type);
        return count == null ? 0 : count;
    }

    private static void assertLifecycleCounts(
            ClassLoader loader, int reducerInvocations) throws Exception {
        for (String implementation : new String[]{
                "mixed.CountByCountry",
                "mixed.Flags",
                "mixed.Count",
                "mixed.Total"}) {
            assertEquals(1, staticInt(loader, implementation, "constructions"),
                    implementation + " constructor calls");
            assertEquals(1, staticInt(loader, implementation, "supplierCalls"),
                    implementation + " supplier() calls");
            assertEquals(1, staticInt(
                    loader, implementation, "supplierInvocations"),
                    implementation + " supplier invocations");
            assertEquals(1, staticInt(loader, implementation, "reducerCalls"),
                    implementation + " reducer() calls");
            assertEquals(reducerInvocations, staticInt(
                    loader, implementation, "reducerInvocations"),
                    implementation + " retained reducer invocations");
        }
    }

    private static int staticInt(
            ClassLoader loader, String className, String fieldName)
            throws Exception {
        Field field = loader.loadClass(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void assertProcessorServiceRegistration()
            throws IOException {
        String resourceName =
                "META-INF/services/javax.annotation.processing.Processor";
        Enumeration<URL> resources = ReductionProcessor.class.getClassLoader()
                .getResources(resourceName);
        boolean registered = false;
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            resource.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (ReductionProcessor.class.getName().equals(line.trim())) {
                        registered = true;
                    }
                }
            }
        }
        assertTrue(registered, "processor service registration is missing");
    }

    private static String objectReductionSource() {
        return lines(
                "package example;",
                "import io.github.jutil.reductionstore.Reduction;",
                "import java.util.HashMap;",
                "import java.util.Map;",
                "import java.util.function.BiFunction;",
                "import java.util.function.Supplier;",
                "public final class Row {",
                "  private final String country;",
                "  public Row(String country) { this.country = country; }",
                "  public String country() { return country; }",
                "}",
                "final class CountByCountry",
                "    implements Reduction<Row, Map<String, Integer>> {",
                "  public Supplier<Map<String, Integer>> supplier() {",
                "    return HashMap::new;",
                "  }",
                "  public BiFunction<Map<String, Integer>, Row,",
                "      Map<String, Integer>> reducer() {",
                "    return (state, row) -> {",
                "      Integer count = state.get(row.country());",
                "      state.put(row.country(), count == null ? 1 : count + 1);",
                "      return state;",
                "    };",
                "  }",
                "}");
    }

    private static String mixedReductionSource() {
        return lines(
                "package mixed;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.HashMap;",
                "import java.util.Map;",
                "import java.util.function.*;",
                "public final class Row {",
                "  private final String country;",
                "  private final boolean flagged;",
                "  private final double amount;",
                "  public Row(String country, boolean flagged, double amount) {",
                "    this.country = country;",
                "    this.flagged = flagged;",
                "    this.amount = amount;",
                "  }",
                "  public String country() { return country; }",
                "  public boolean flagged() { return flagged; }",
                "  public double amount() { return amount; }",
                "}",
                "final class CountByCountry",
                "    implements Reduction<Row, Map<String, Integer>> {",
                "  static int constructions;",
                "  static int supplierCalls;",
                "  static int supplierInvocations;",
                "  static int reducerCalls;",
                "  static int reducerInvocations;",
                "  CountByCountry() { constructions++; }",
                "  public Supplier<Map<String, Integer>> supplier() {",
                "    supplierCalls++;",
                "    return () -> {",
                "      supplierInvocations++;",
                "      return new HashMap<String, Integer>();",
                "    };",
                "  }",
                "  public BiFunction<Map<String, Integer>, Row,",
                "      Map<String, Integer>> reducer() {",
                "    reducerCalls++;",
                "    return (state, row) -> {",
                "      reducerInvocations++;",
                "      Integer count = state.get(row.country());",
                "      state.put(row.country(), count == null ? 1 : count + 1);",
                "      return state;",
                "    };",
                "  }",
                "}",
                "final class Flags implements IntReduction<Row> {",
                "  static int constructions;",
                "  static int supplierCalls;",
                "  static int supplierInvocations;",
                "  static int reducerCalls;",
                "  static int reducerInvocations;",
                "  Flags() { constructions++; }",
                "  public IntSupplier supplier() {",
                "    supplierCalls++;",
                "    return () -> { supplierInvocations++; return 2; };",
                "  }",
                "  public IntReducer<Row> reducer() {",
                "    reducerCalls++;",
                "    return (state, row) -> {",
                "      reducerInvocations++;",
                "      return state + (row.flagged() ? 1 : 0);",
                "    };",
                "  }",
                "}",
                "final class Count implements LongReduction<Row> {",
                "  static int constructions;",
                "  static int supplierCalls;",
                "  static int supplierInvocations;",
                "  static int reducerCalls;",
                "  static int reducerInvocations;",
                "  Count() { constructions++; }",
                "  public LongSupplier supplier() {",
                "    supplierCalls++;",
                "    return () -> { supplierInvocations++; return 10L; };",
                "  }",
                "  public LongReducer<Row> reducer() {",
                "    reducerCalls++;",
                "    return (state, row) -> {",
                "      reducerInvocations++;",
                "      return state + 1L;",
                "    };",
                "  }",
                "}",
                "final class Total implements DoubleReduction<Row> {",
                "  static int constructions;",
                "  static int supplierCalls;",
                "  static int supplierInvocations;",
                "  static int reducerCalls;",
                "  static int reducerInvocations;",
                "  Total() { constructions++; }",
                "  public DoubleSupplier supplier() {",
                "    supplierCalls++;",
                "    return () -> { supplierInvocations++; return 0.5d; };",
                "  }",
                "  public DoubleReducer<Row> reducer() {",
                "    reducerCalls++;",
                "    return (state, row) -> {",
                "      reducerInvocations++;",
                "      return state + row.amount();",
                "    };",
                "  }",
                "}");
    }

    private static String indirectReductionSource() {
        return lines(
                "package indirect;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.DoubleSupplier;",
                "public final class Row {",
                "  private final double amount;",
                "  public Row(double amount) { this.amount = amount; }",
                "  public double amount() { return amount; }",
                "}",
                "abstract class BaseTotal<P> implements DoubleReduction<P> {",
                "  public DoubleSupplier supplier() { return () -> 1.0d; }",
                "  public DoubleReducer<P> reducer() {",
                "    return (state, value) -> state + ((Row) value).amount();",
                "  }",
                "}",
                "final class TotalAmount extends BaseTotal<Row> {}");
    }

    private static String failureOrderingSource() {
        return lines(
                "package failure;",
                "import io.github.jutil.reductionstore.*;",
                "import java.util.function.LongSupplier;",
                "public final class Row {}",
                "final class AEarlier implements LongReduction<Row> {",
                "  static int invocations;",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> { invocations++; return state + 1; };",
                "  }",
                "}",
                "final class MFailing implements LongReduction<Row> {",
                "  static int invocations;",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> {",
                "      invocations++;",
                "      throw new IllegalStateException(\"deliberate failure\");",
                "    };",
                "  }",
                "}",
                "final class ZLater implements LongReduction<Row> {",
                "  static int invocations;",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Row> reducer() {",
                "    return (state, row) -> { invocations++; return state + 1; };",
                "  }",
                "}");
    }

    private static String zeroAnnotationSource() {
        return lines(
                "package zero;",
                "import io.github.jutil.reductionstore.LongReducer;",
                "import io.github.jutil.reductionstore.LongReduction;",
                "import java.util.function.LongSupplier;",
                "public final class Event {}",
                "final class Count implements LongReduction<Event> {",
                "  public LongSupplier supplier() { return () -> 0L; }",
                "  public LongReducer<Event> reducer() {",
                "    return (state, event) -> state + 1L;",
                "  }",
                "}");
    }
}
