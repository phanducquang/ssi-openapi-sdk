package io.github.phanducquang.ssi.trading.fco;

import io.github.phanducquang.ssi.trading.enums.OrderSide;
import io.github.phanducquang.ssi.trading.enums.OrderStatus;
import io.github.phanducquang.ssi.trading.enums.OrderType;

public record FcoOrder(
        String fcoId,
        String accountNo,
        double quantity,
        String price,
        String symbol,
        OrderSide side,
        OrderType orderType,
        boolean mainOrder,
        boolean attachedOrder,
        String createdTime,
        String updatedTime,
        String uniqueId,
        String orderId,
        double matchedQuantity,
        double osQuantity,
        double avgPrice,
        OrderStatus status,
        String detail) {
}
