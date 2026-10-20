package vn.ssi.openapi.marketdata.model;

import java.util.List;

/** One page of SSI Corporate Actions, including server pagination metadata. */
public record CorporateActionsPage(
        int pageSize,
        int pageIndex,
        int pagesCount,
        int itemsCount,
        List<CorporateAction> data) {
    public CorporateActionsPage {
        data = data == null ? List.of() : List.copyOf(data);
    }
}
