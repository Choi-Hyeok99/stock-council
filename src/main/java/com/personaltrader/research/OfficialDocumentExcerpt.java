package com.personaltrader.research;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipInputStream;

/** Bounded publisher-text excerpts, not an LLM summary or a verified financial table parser. */
public final class OfficialDocumentExcerpt {
    public static final int VERSION=3;
    private static final Pattern TITLES=Pattern.compile("<TITLE\\b[^>]{0,2048}>([\\s\\S]{0,3000}?)</TITLE>");
    public record Section(String category,String title,String text,boolean truncated){}
    public record Document(String reportName,List<Section> sections){}
    public static Document extract(byte[] compressed,String receipt,String corp)throws IOException{
        if(!receipt.matches("[0-9]{14}")||!corp.matches("[0-9]{8}"))throw new IOException("identity");
        int count=0,total=0;String xml=null;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(compressed))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                if(++count>12)throw new IOException("entries");
                byte[] bytes=zip.readNBytes(40000001);total+=bytes.length;
                if(bytes.length>40000000||total>60000000)throw new IOException("size");
                if(entry.getName().equals(receipt+".xml")){
                    if(xml!=null)throw new IOException("duplicate");
                    xml=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                }
            }
        }
        if(xml==null||Pattern.compile("<!\\s*(DOCTYPE|ENTITY)",Pattern.CASE_INSENSITIVE).matcher(xml).find())throw new IOException("markup");
        var name=Pattern.compile("<DOCUMENT-NAME[^>]{0,2048}>\\s*(사업보고서|반기보고서|분기보고서)\\s*</DOCUMENT-NAME>").matcher(xml);
        if(!name.find()||!Pattern.compile("<COMPANY-NAME[^>]{0,2048}AREGCIK=\""+corp+"\"[^>]{0,2048}>").matcher(xml).find())throw new IOException("identity");
        String reportName=name.group(1);var titles=TITLES.matcher(xml);List<String> names=new ArrayList<>();List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        while(titles.find()){if(names.size()>=20000)throw new IOException("titles");names.add(plain(titles.group(1)));starts.add(titles.start());ends.add(titles.end());}
        Map<String,List<Section>> candidates=new LinkedHashMap<>();
        for(int i=0;i<names.size();i++){
            String title=names.get(i),category=category(title);if(category==null)continue;
            var categoryCandidates=candidates.computeIfAbsent(category,key->new ArrayList<>());
            if(categoryCandidates.size()>=8)continue;
            int end=i+1<names.size()?starts.get(i+1):xml.length();
            String raw=xml.substring(ends.get(i),Math.min(end,ends.get(i)+1000000));String text=plain(raw);
            // A parent heading can have no body; do not publish a heading/placeholder as evidence.
            if(text.length()<40||placeholder(text))continue;
            String excerpt=excerpt(raw,category);
            if(excerpt.length()<40)continue;
            categoryCandidates.add(new Section(category,title,excerpt,text.length()>excerpt.length()));
        }
        List<Section> result=new ArrayList<>();
        for(var entry:candidates.entrySet()){
            var sections=entry.getValue();if(sections.isEmpty())continue;
            if(entry.getKey().equals("OPERATIONS")&&sections.size()>1){
                // Product mix and sales/order-book headings carry different facts. Preserve both labels.
                var first=sections.getFirst();var second=sections.stream().skip(1).filter(s->!s.text().equals(first.text())).findFirst().orElse(null);
                if(second!=null){
                    String text=clip(first.title(),100)+" · "+clip(first.text(),560)+" / "+clip(second.title(),100)+" · "+clip(second.text(),560);
                    result.add(new Section(entry.getKey(),first.title()+" / "+second.title(),clip(text,1400),true));continue;
                }
            }
            // Prefer a topic-bearing body over a generic first section or an omission notice.
            result.add(sections.stream().max(Comparator.comparingInt(s->relevance(s.text(),entry.getKey()))).orElseThrow());
        }
        if(result.isEmpty())throw new IOException("no excerpts");
        return new Document(reportName,List.copyOf(result));
    }
    private static String category(String title){
        if(title.matches(".*(사업의 개요|사업의 내용).*"))return "BUSINESS";
        if(title.matches(".*(주요 제품 및 서비스|영업의 현황|매출 및 수주).*"))return "OPERATIONS";
        if(title.matches(".*(재무건전성|자본적정성|지급여력).*"))return "CAPITAL";
        if(title.matches(".*(외부감사에 관한|감사인의 감사의견).*"))return "AUDIT";
        if(title.matches(".*(위험관리 및|금융위험관리).*"))return "RISK";
        if(title.matches(".*(연구개발|연구 개발|주요계약 및).*"))return "TECHNOLOGY";
        if(title.matches(".*(이사의 경영진단|재무상태 및 영업실적).*"))return "MANAGEMENT";
        return null;
    }
    private static String excerpt(String raw,String category){
        // Keep complete small capital/audit tables, including units and column headers.
        // Never interpret a clipped cell as a current-period numeric observation.
        if(Set.of("CAPITAL","AUDIT","OPERATIONS").contains(category)){
            String text=plain(raw);return clip(text,1400);
        }
        var paragraphs=Pattern.compile("<P\\b[^>]{0,2048}>([\\s\\S]{0,12000}?)</P>").matcher(raw);
        record Paragraph(int index,String text,int score){}
        List<Paragraph> candidates=new ArrayList<>();Set<String> seen=new HashSet<>();int scanned=0;
        while(paragraphs.find()&&++scanned<=20000){
            String text=plain(paragraphs.group(1));
            if(text.length()<45||placeholder(text)||text.matches("[\\d\\s.,%|()\\-]+")||!seen.add(text))continue;
            int score=relevance(text,category);
            // A joint major-contract/R&D heading must not turn an old real-estate contract into technology evidence.
            if(category.equals("TECHNOLOGY")&&score<=0)continue;
            candidates.add(new Paragraph(scanned,text,score));
        }
        var selected=candidates.stream().sorted(Comparator.comparingInt(Paragraph::score).reversed().thenComparingInt(Paragraph::index))
            .limit(3).sorted(Comparator.comparingInt(Paragraph::index)).map(p->focus(p.text(),category,650)).toList();
        if(selected.isEmpty()){
            String fallback=plain(raw);if(placeholder(fallback)||(category.equals("TECHNOLOGY")&&relevance(fallback,category)<=0))return "";
            return focus(fallback,category,1400);
        }
        return clip(String.join(" / ",selected),1400);
    }
    private static boolean placeholder(String text){
        return text.length()<260&&text.matches(".*(기재를?\\s*생략|기재하지\\s*않|사업보고서에\\s*기재\\s*예정|사업보고서를?\\s*참고|해당사항이?\\s*없습니다).*");
    }
    private static int relevance(String text,String category){
        String keywords=switch(category){
            case "TECHNOLOGY" -> "연구개발|연구 개발|R&D|기술개발|임상|신약|특허|상용화|신제품|개발비|연구비|연구인력|연구 인력";
            case "RISK" -> "환율|외화|원재료|민감도|차입금|유동성|소송|규제|집중|노출|매출채권|신용등급";
            case "MANAGEMENT" -> "영업이익|매출|현금흐름|증가|감소|원인|수익성|전망|투자";
            case "BUSINESS" -> "사업|제품|서비스|수익|매출|고객|생산|경쟁|시장";
            case "OPERATIONS" -> "매출|수주|제품|서비스|생산|판매";
            default -> "당기|반기|연결|감사의견|검토|순자본|자본비율";
        };
        var matches=Pattern.compile(keywords,Pattern.CASE_INSENSITIVE).matcher(text);int score=0;
        while(matches.find()&&score<60)score+=4;
        if(!category.equals("TECHNOLOGY")){
            if(text.matches(".*[0-9].*"))score+=3;
            if(text.contains("당사")||text.contains("연결"))score+=2;
            if(text.contains("이란")||text.contains("의미합니다"))score-=5;
        }
        return score;
    }
    private static String focus(String text,String category,int limit){
        if(!category.equals("TECHNOLOGY")||text.length()<=limit)return clip(text,limit);
        var topic=Pattern.compile("연구개발|연구 개발|R&D|기술개발|임상|신약|특허|상용화|신제품|개발비|연구비|연구인력|연구 인력",Pattern.CASE_INSENSITIVE).matcher(text);
        if(!topic.find()||topic.start()<limit/2)return clip(text,limit);
        int start=Math.max(0,topic.start()-100),sentence=text.lastIndexOf(". ",topic.start());
        if(sentence>=start)start=sentence+2;
        return "…[앞부분 생략] "+clip(text.substring(start),Math.max(40,limit-12));
    }
    static String plain(String value){
        return value.replaceAll("(?i)</(?:TD|TH|TE)>"," | ").replaceAll("(?i)</(?:P|TR|TABLE)>"," / ")
            .replaceAll("<[^>]{0,8192}>"," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&quot;","\"")
            .replace("&lt;","<").replace("&gt;",">").replaceAll("[\\p{Cntrl}]"," ").replaceAll("[\\s　]+"," ")
            .replaceAll("(?: / ){2,}"," / ").strip();
    }
    private static String clip(String text,int size){return text.length()<=size?text:text.substring(0,size)+" …[이하 생략]";}
    private OfficialDocumentExcerpt(){}
}
