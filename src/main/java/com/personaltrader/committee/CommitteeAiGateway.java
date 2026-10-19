package com.personaltrader.committee;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.math.*;
import java.util.*;
import com.personaltrader.research.PublicInformationService;

/** Responses API, no tools, no order authority, no API response/header logging and no retry. */
@Component
public class CommitteeAiGateway {
    private static final URI ENDPOINT=URI.create("https://api.openai.com/v1/responses");
    private final OpenAiCredentials credentials;private final CommitteeAiLedger ledger;private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public CommitteeAiGateway(OpenAiCredentials credentials,CommitteeAiLedger ledger,ObjectMapper mapper){this.credentials=credentials;this.ledger=ledger;this.mapper=mapper;}
    public boolean available(){return credentials.available();}
    public Prepared prepare(String role,String phase,String instructions,String input,Set<String> ids){
        String model=Set.of("FINANCE","RISK","REPORT").contains(role)?"gpt-5.4-mini":"gpt-5.6-luna";
        int maxOutput="SUMMARY".equals(phase)?1600:1800;
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("instructions",instructions);
        body.put("input",List.of(Map.of("role","user","content",input)));body.put("max_output_tokens",maxOutput);
        body.put("reasoning",Map.of("effort","none"));body.put("store",false);body.put("stream",false);
        body.put("service_tier","default");body.put("tools",List.of());
        body.put("text",Map.of("format",Map.of("type","json_schema","name","committee_"+phase.toLowerCase(Locale.ROOT),"strict",true,"schema",schema("SUMMARY".equals(phase),ids,role,phase))));
        String encoded=json(body);int bytes=encoded.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>64000)throw new IllegalArgumentException("입력 크기 확인 필요");
        // A conservative byte-based bound plus framing/schema margin; provider hard limit is separate.
        int inputBound=bytes+4096;BigDecimal reserve=cost(model,inputBound,maxOutput,0,inputBound);
        return new Prepared(model,encoded,PublicInformationService.hash(encoded),inputBound,maxOutput,reserve);
    }
    public Result submit(long reservation,Prepared request){
        var secret=credentials.read().filter(OpenAiCredentials.Secret::enabled);
        if(secret.isEmpty()){ledger.notSent(reservation);return Result.failure("CREDENTIALS_WAIT","로컬 API 키·활성화·공급자 한도 설정 확인 필요");}
        HttpRequest http=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(60))
            .header("Authorization","Bearer "+secret.get().key()).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(request.body(),StandardCharsets.UTF_8)).build();
        if(!ledger.claimSend(reservation,request.hash())){ledger.releaseUnclaimed(reservation);return Result.failure("CLAIM_WAIT","일시중지·종료 또는 이미 처리한 호출 · 재전송 없음");}
        boolean settled=false;
        try{
            HttpResponse<byte[]> response=client.send(http,info->new BoundedResponseSubscriber(1000000));
            if(Set.of(400,401,403,404,429).contains(response.statusCode())){
                String code=switch(response.statusCode()){case 401->"AUTH_REJECTED";case 403->"ACCESS_REJECTED";case 404->"MODEL_UNAVAILABLE";case 429->"LIMIT_REJECTED";default->"FORMAT_REJECTED";};
                ledger.rejected(reservation,code);return Result.failure(code,"API 키·결제/모델 권한·호출 한도 확인 필요 · 자동 재시도 없음");
            }
            if(response.statusCode()!=200){ledger.unknown(reservation);return Result.failure("DELIVERY_UNKNOWN","공급자 결과 확인 대기 · 예약액 유지·자동 재전송 없음");}
            JsonNode data=mapper.readTree(response.body()),usage=data.path("usage");
            int in=tokens(usage,"input_tokens"),out=tokens(usage,"output_tokens");
            int cached=optionalTokens(usage.path("input_tokens_details"),"cached_tokens"),writes=optionalTokens(usage.path("input_tokens_details"),"cache_write_tokens");
            String actualModel=data.path("model").asText(),tier=data.path("service_tier").asText();
            if((long)cached+writes>in||(!actualModel.equals(request.model())&&!actualModel.startsWith(request.model()+"-"))||(!tier.isBlank()&&!"default".equals(tier)))throw new IllegalArgumentException();
            BigDecimal charge=cost(request.model(),in,out,cached,writes);
            boolean bounded=in<=request.inputBound()&&out<=request.maxOutput()&&charge.compareTo(request.reservedUsd())<=0;
            String status=data.path("status").asText();ledger.settle(reservation,in,out,cached,writes,charge,bounded?status:"BOUND_EXCEEDED");
            settled=true;
            if(!bounded)return Result.failure("BOUND_EXCEEDED","실제 사용량/가격 범위 확인 필요 · 추가 호출 보류");
            if(!"completed".equals(status))return Result.failure("INCOMPLETE","응답이 완성되지 않았어요. 비용 기록 후 검토 보류 · 자동 재시도 없음");
            StringBuilder output=new StringBuilder();boolean refusal=false;
            for(JsonNode item:data.path("output"))if("message".equals(item.path("type").asText()))for(JsonNode content:item.path("content")){
                if("refusal".equals(content.path("type").asText()))refusal=true;
                if("output_text".equals(content.path("type").asText()))output.append(content.path("text").asText());
            }
            if(refusal||output.isEmpty()||output.length()>24000)return Result.failure("OUTPUT_UNAVAILABLE","모델 답변 확인 필요 · 거절/미완성 답변을 표로 만들지 않아요.");
            JsonNode structured=mapper.readTree(output.toString());if(!structured.isObject())return Result.failure("OUTPUT_INVALID","구조화 답변 확인 필요");
            return new Result(true,"completed",null,actualModel,in,out,charge,structured);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();ledger.unknown(reservation);return Result.failure("DELIVERY_UNKNOWN","호출 중단 · 예약액 유지·재전송 없음");}
        catch(Exception failure){if(settled)return Result.failure("OUTPUT_INVALID","구조화 답변 확인 필요 · 실제 사용량 비용은 기록했어요.");ledger.unknown(reservation);return Result.failure("DELIVERY_UNKNOWN","응답/사용량 확인 대기 · 예약액 유지·자동 재전송 없음");}
    }
    private static int tokens(JsonNode value,String name){JsonNode n=value.path(name);if(!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<0)throw new IllegalArgumentException();return n.intValue();}
    private static int optionalTokens(JsonNode value,String name){return value.has(name)?tokens(value,name):0;}
    static Map<String,Object> schema(boolean summary,Set<String> ids){return schema(summary,ids,null,null);}
    private static Map<String,Object> schema(boolean summary,Set<String> ids,String role,String phase){
        Map<String,Object> properties=new LinkedHashMap<>();
        if(summary){properties.put("conclusion",Map.of("type","string","enum",List.of("REVIEW_BUY","REVIEW_SELL","KEEP_WATCH","NO_ACTION")));properties.put("summary",text(250));properties.put("reasons",array(text(120),3));properties.put("cautions",array(text(120),3));
            properties.put("assessment",Map.of("type","string","enum",List.of("CONDITIONAL_INTEREST","UNATTRACTIVE","DATA_GAP","MIXED")));
            properties.put("bullCase",text(180));properties.put("bearCase",text(180));properties.put("reconsiderWhen",text(180));}
        else{properties.put("vote",Map.of("type","string","enum",List.of("APPROVE","REJECT","ABSTAIN")));properties.put("summary",text(170));properties.put("facts",array(text(120),2));properties.put("inferences",array(text(120),2));properties.put("risks",array(text(120),2));properties.put("changedReason",text(150));
            Map<String,Object> d=new LinkedHashMap<>();
            d.put("questionTo",Map.of("type","string","enum",java.util.stream.Stream.concat(java.util.stream.Stream.of(""),CommitteeContract.ROLES.stream().map(CommitteeContract.Role::key)).toList()));
            d.put("question",text(160));d.put("replyTo",d.get("questionTo"));d.put("reply",text(200));d.put("counterpoint",text(160));d.put("changeCondition",text(160));
            if(role!=null){
                d.put("questionTo",Map.of("type","string","enum",List.of("INDEPENDENT".equals(phase)?CommitteeContract.questionTarget(role):"")));
                d.put("replyTo",Map.of("type","string","enum",List.of("DEBATE".equals(phase)?CommitteeContract.questioner(role):"")));
                d.put("question","INDEPENDENT".equals(phase)?Map.of("type","string","minLength",1,"maxLength",160):Map.of("type","string","enum",List.of("")));
                d.put("reply","DEBATE".equals(phase)?Map.of("type","string","minLength",1,"maxLength",200):Map.of("type","string","enum",List.of("")));
                d.put("changeCondition",Map.of("type","string","minLength",1,"maxLength",160));
                if(!"INDEPENDENT".equals(phase))d.put("counterpoint",Map.of("type","string","minLength",1,"maxLength",160));
            }
            properties.put("discussion",Map.of("type","object","properties",d,"required",List.copyOf(d.keySet()),"additionalProperties",false));}
        properties.put("evidenceIds",array(Map.of("type","string","enum",ids.stream().sorted().toList()),6));
        return Map.of("type","object","properties",properties,"required",List.copyOf(properties.keySet()),"additionalProperties",false);
    }
    private static Map<String,Object> text(int length){return Map.of("type","string","maxLength",length);}
    private static Map<String,Object> array(Map<String,Object> item,int count){return Map.of("type","array","items",item,"maxItems",count);}
    private static BigDecimal cost(String model,int in,int out,int cached,int writes){
        boolean mini=model.startsWith("gpt-5.4-mini");BigDecimal input=new BigDecimal(mini?"0.75":"0.20"),output=new BigDecimal(mini?"4.50":"1.20"),cache=new BigDecimal(mini?"0.075":"0.02");
        return input.multiply(BigDecimal.valueOf(in-cached-writes)).add(input.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(writes)))
            .add(cache.multiply(BigDecimal.valueOf(cached))).add(output.multiply(BigDecimal.valueOf(out))).divide(new BigDecimal("1000000"),8,RoundingMode.CEILING);
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("입력 형식 확인 필요");}}
    public record Prepared(String model,String body,String hash,int inputBound,int maxOutput,BigDecimal reservedUsd){@Override public String toString(){return "Prepared AI request [redacted]";}}
    public record Result(boolean ok,String outcome,String issue,String model,int inputTokens,int outputTokens,BigDecimal costUsd,JsonNode output){
        @Override public String toString(){return "AI result [body redacted]";}
        static Result failure(String outcome,String issue){return new Result(false,outcome,issue,null,0,0,null,null);}
    }
}
