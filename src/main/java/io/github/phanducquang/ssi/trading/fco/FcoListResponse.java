package io.github.phanducquang.ssi.trading.fco;

import java.util.List;

public record FcoListResponse(
        int pageIndex,
        int pageSize,
        int itemsCount,
        int pagesCount,
        List<FcoInfo> fcoList) {

    public FcoListResponse {
        fcoList = fcoList == null ? List.of() : List.copyOf(fcoList);
    }
}
