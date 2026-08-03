package reductions;

import io.github.jutil.reductionstore.IntReducer;
import io.github.jutil.reductionstore.IntReduction;
import java.util.function.IntSupplier;
import model.Row;

public final class UnlistedReduction implements IntReduction<Row> {

    public static int constructions;
    public static int invocations;

    public UnlistedReduction() {
        constructions++;
    }

    @Override
    public IntSupplier supplier() {
        return () -> 0;
    }

    @Override
    public IntReducer<Row> reducer() {
        return (state, row) -> {
            invocations++;
            return state + 1;
        };
    }
}
