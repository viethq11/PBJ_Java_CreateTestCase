package com.pbj.v2.contract;

import java.util.List;

public final class KnownContracts {
    private KnownContracts() {
    }

    public static ProblemContract powerTower() {
        ScalarField n = new ScalarField("n", Bound.of(1), Bound.of(50));
        ScalarField m = new ScalarField("m", Bound.of(1), Bound.of(30));
        Bound one = Bound.of(1);
        Bound nRef = Bound.ref("n");
        CommandVariant update = new CommandVariant("UPDATE", List.of(
                new ScalarField("x", one, nRef),
                new ScalarField("y", one, nRef),
                new ScalarField("z", one, nRef),
                new ScalarField("w", Bound.of(-1_000_000_000L), Bound.of(1_000_000_000L))
        ));
        CommandVariant query = new CommandVariant("QUERY", List.of(
                new ScalarField("x1", one, nRef),
                new ScalarField("y1", one, nRef),
                new ScalarField("z1", one, nRef),
                new ScalarField("x2", one, nRef),
                new ScalarField("y2", one, nRef),
                new ScalarField("z2", one, nRef)
        ));
        return new ProblemContract(
                "Power Tower",
                true,
                Bound.of(1),
                Bound.of(5),
                List.of(
                        InputSection.scalars(List.of(n, m)),
                        InputSection.commands("m", List.of(update, query))
                ),
                "Print one integer sum for each QUERY operation.");
    }
}
