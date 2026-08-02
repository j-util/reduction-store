package example;

import io.github.jutil.reductionstore.LongReducer;
import io.github.jutil.reductionstore.LongReduction;
import java.util.function.LongSupplier;

public final class Count implements LongReduction<Row> {

    @Override
    public LongSupplier supplier() {
        return () -> 0L;
    }

    @Override
    public LongReducer<Row> reducer() {
        return (count, row) -> count + 1L;
    }
}
