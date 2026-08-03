package reductions;

import io.github.jutil.reductionstore.LongReducer;
import io.github.jutil.reductionstore.LongReduction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import model.Row;

public final class CountReduction implements LongReduction<Row> {

    public static final List<Row> received = new ArrayList<Row>();

    @Override
    public LongSupplier supplier() {
        return () -> 0L;
    }

    @Override
    public LongReducer<Row> reducer() {
        return (count, row) -> {
            received.add(row);
            return count + 1L;
        };
    }
}
