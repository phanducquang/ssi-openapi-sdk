package vn.ssi.openapi.trading.fco;

import vn.ssi.openapi.trading.enums.OrderSide;
import vn.ssi.openapi.trading.enums.OrderStatus;
import vn.ssi.openapi.trading.enums.OrderType;

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
