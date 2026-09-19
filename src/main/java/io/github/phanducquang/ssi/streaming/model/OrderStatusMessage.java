package io.github.phanducquang.ssi.streaming.model;

import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;

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
