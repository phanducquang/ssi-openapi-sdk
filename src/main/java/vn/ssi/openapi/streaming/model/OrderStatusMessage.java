package vn.ssi.openapi.streaming.model;

import vn.ssi.openapi.trading.enums.OrderSide;
import vn.ssi.openapi.trading.enums.OrderStatus;
import vn.ssi.openapi.trading.enums.OrderType;

public record OrderStatusMessage(
        String accountNo,
        String clientRequestId,
        String orderId,
        String symbol,
        OrderSide side,
        OrderType orderType,
        Object price,
        long quantity,
        long osQuantity,
        long filledQuantity,
        long cancelQuantity,
        OrderStatus status,
        String inputTime,
        String modifyTime,
        String message) {
}
