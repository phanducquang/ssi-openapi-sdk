package io.github.phanducquang.ssi.portfolio.model;

public record EquityAccountBalance(
        String accountNo,
        double accountBalance,
        double totalDebt,
        double interestLoan,
        double overdueFeeLoan,
        double withdrawable,
        double onHoldCash,
        double sellUnmatched,
        double sellT0,
        double sellT1,
        double sellT2,
        double buyUnmatched,
        double buyT0,
        double buyT1,
        double buyT2,
        double advanceCashT0,
        double advanceCashT1,
        double holdSubscription,
        double dividend) {
}
