package com.personaltrader.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.personaltrader.research.InvestmentSignalEngine.NumericCheck;

/** Historical JSON format and version only. Automatic allocation calculations have been retired. */
public final class PaperAllocationService {
    public static final String VERSION="PAPER_SIZE_1P5_3_5M_V2";
    private PaperAllocationService(){}

    public record Decision(String version,String tier,BigDecimal target,Long financialId,Instant financialAt,
        List<NumericCheck> checks,List<String> sources,String reason){}
}
