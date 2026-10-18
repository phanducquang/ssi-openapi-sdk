package vn.ssi.openapi.portfolio.model;

import vn.ssi.openapi.trading.enums.OrderSide;
import vn.ssi.openapi.trading.enums.OrderStatus;
import vn.ssi.openapi.trading.enums.OrderType;

public record Order(
        String accountNo,
        String clientRequestId,
        String orderId,
        String symbol,
        OrderSide side,
        OrderType orderType,
        Object price,
        double avgPrice,
        long quantity,
        long osQuantity,
        long filledQuantity,
        long cancelQuantity,
        OrderStatus status,
        String inputTime,
        String modifyTime,
        String message) {
}
