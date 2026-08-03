package composition;

import java.math.BigDecimal;
import model.Row;
import reductions.CountReduction;
import reductions.TotalReduction;
import reductions.UnlistedReduction;

public final class Application {

    private Application() {
    }

    public static void main(String[] arguments) {
        RowReductionStore store = new RowReductionStore();
        Row[] rows = new Row[]{new Row(10L), new Row(20L), new Row(30L)};
        for (Row row : rows) {
            store.add(row);
        }

        if (CountReduction.received.size() != rows.length
                || TotalReduction.received.size() != rows.length) {
            throw new AssertionError(
                    "Expected both selected reductions to receive every row");
        }
        for (int index = 0; index < rows.length; index++) {
            if (CountReduction.received.get(index) != rows[index]
                    || TotalReduction.received.get(index) != rows[index]) {
                throw new AssertionError(
                        "Selected reductions did not receive row " + index);
            }
        }
        if (store.countReduction() != 3L) {
            throw new AssertionError(
                    "Expected count 3 but got " + store.countReduction());
        }
        if (!BigDecimal.valueOf(60L).equals(store.totalReduction())) {
            throw new AssertionError(
                    "Expected total 60 but got " + store.totalReduction());
        }
        if (UnlistedReduction.constructions != 0
                || UnlistedReduction.invocations != 0) {
            throw new AssertionError(
                    "The unlisted reduction was constructed or executed");
        }
    }
}
