package io.github.phanducquang.ssi.portfolio.model;

public record DerivativeAccountBalance(
        String accountNo,
        double accountBalance,
        double fee,
        double commission,
        double interest,
        double extInterest,
        double loan,
        double deliveryAmount,
        double floatingPl,
        double tradingPl,
        double totalPl,
        double withdrawable,
        double cashSsi,
        double validNonCashSsi,
        double cashWithdrawableSsi,
        double cashVsdc,
        double validNonCashVsdc,
        double cashWithdrawableVsdc) {
}
