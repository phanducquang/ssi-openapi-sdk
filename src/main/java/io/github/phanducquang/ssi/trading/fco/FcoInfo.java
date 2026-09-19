package io.github.phanducquang.ssi.trading.fco;

public record FcoInfo(
        String fcoId,
        String clientId,
        String accountNo,
        long quantity,
        String price,
        String priceSlip,
        String symbol,
        FcoType type,
        String fromDate,
        String toDate,
        long matchedQuantity,
        boolean placeOrder,
        FcoStatus status,
        String detail,
        FcoParams params) {
}
