package com.personaltrader.research;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PublicFeedBoundaryTest {
 @Test void officialFeedLinksRemainBoundToTheirPublisher(){
  assertThat(PublicInformationService.safeUrl("http://www.federalreserve.gov/newsevents/pressreleases/monetary.htm","미 연준 통화정책")).startsWith("https://www.federalreserve.gov/");
  for(String bad:new String[]{"https://www.federalreserve.gov.evil.invalid/","https://user"+(char)64+"www.federalreserve.gov/","https://www.federalreserve.gov:9443/","http://127.0.0.1/","javascript:alert(1)"})assertThat(PublicInformationService.safeUrl(bad,"미 연준 통화정책")).isNull();
  assertThat(PublicInformationService.safeUrl("https://www.hankyung.com/article/example","미 연준 통화정책")).isNull();
 }
}
