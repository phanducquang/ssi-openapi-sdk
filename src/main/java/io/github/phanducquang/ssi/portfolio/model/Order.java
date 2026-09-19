package io.github.phanducquang.ssi.portfolio.model;

import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;

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
