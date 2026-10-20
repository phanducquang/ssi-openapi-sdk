package vn.ssi.openapi.marketdata.model;

/** One SSI Corporate Actions event; a pending event may have blank dates or ratio. */
public record CorporateAction(
        String symbol,
        String eventTitle,
        String exrightDate,
        String recordDate,
        String issueDate,
        String exchange,
        String ratio) {
}
