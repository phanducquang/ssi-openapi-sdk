package vn.ssi.openapi.portfolio.model;

import java.util.List;

public record DerivativePositions(
        List<DerivativePosition> openPositions,
        List<DerivativePosition> closedPositions) {
}
