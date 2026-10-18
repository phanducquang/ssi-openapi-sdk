package vn.ssi.openapi.trading.fco;

import vn.ssi.openapi.trading.enums.OrderSide;

public record FcoParams(
        Double stopPrice,
        OrderSide side,
        Double activePrice,
        Double trailingAmount,
        Double tpActivePrice,
        Double slActivePrice,
        String tpPrice,
        String slPrice,
        Double tpSlip,
        Double slSlip,
        FcoOperator operator) {
}
