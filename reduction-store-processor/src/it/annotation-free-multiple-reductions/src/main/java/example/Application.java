package example;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class Application {

    private Application() {
    }

    public static void main(String[] arguments) {
        RowReductionStore store = new RowReductionStore();
        SourceRow[] sourceRows = new SourceRow[]{
                new SourceRow("10"),
                new SourceRow("20"),
                new SourceRow("30")
        };
        AtomicInteger mappingCount = new AtomicInteger();
        Function<SourceRow, Row> mapper = sourceRow -> {
            mappingCount.incrementAndGet();
            return new Row(Long.parseLong(sourceRow.rawValue()));
        };
        Row[] mappedRows = new Row[sourceRows.length];

        for (int index = 0; index < sourceRows.length; index++) {
            Row mappedRow = mapper.apply(sourceRows[index]);
            mappedRows[index] = mappedRow;
            store.add(mappedRow);
        }

        if (mappingCount.get() != sourceRows.length) {
            throw new AssertionError(
                    "Expected one mapping per source row but got "
                            + mappingCount.get());
        }
        if (Count.received.size() != mappedRows.length
                || Total.received.size() != mappedRows.length) {
            throw new AssertionError(
                    "Expected both reducers to receive every mapped row");
        }
        for (int index = 0; index < mappedRows.length; index++) {
            if (Count.received.get(index) != mappedRows[index]
                    || Total.received.get(index) != mappedRows[index]) {
                throw new AssertionError(
                        "Reducers did not receive mapped row " + index);
            }
        }

        if (store.count() != 3L) {
            throw new AssertionError(
                    "Expected count 3 but got " + store.count());
        }
        if (store.total() != 60L) {
            throw new AssertionError(
                    "Expected total 60 but got " + store.total());
        }
    }
}
