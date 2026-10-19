package com.personaltrader.web;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
class GuideRoutesTest {
    @Test void everyTemplateGuideLinkHasAnAllowedLocalDocument()throws Exception{
        var controller=new OperationsController();var names=new HashSet<String>();
        try(var paths=Files.list(Path.of("src/main/resources/templates"))){for(var path:paths.toList()){
            var m=Pattern.compile("href=\"/guide/([a-z-]+)\"").matcher(Files.readString(path));while(m.find())names.add(m.group(1));
        }}
        assertThat(names).contains("calendar","weekend","financials","berkshire","value-research");
        for(var name:names){var model=new ExtendedModelMap();assertThat(controller.guide(name,model)).isEqualTo("guide");assertThat(model.get("content").toString()).doesNotStartWith("# 로컬 운영 기록 제외");}
        assertThatThrownBy(()->controller.guide("unapproved",new ExtendedModelMap())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
