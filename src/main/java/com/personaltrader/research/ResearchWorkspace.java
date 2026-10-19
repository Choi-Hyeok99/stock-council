package com.personaltrader.research;
import com.personaltrader.broker.ExecutionBrokerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Private research identity, independent of order/account setup. Existing research retains its identity. */
@Service
public final class ResearchWorkspace {
 private final String id;
 public ResearchWorkspace(JdbcTemplate jdbc,ExecutionBrokerClient broker,@Value("${execution.environment:PAPER}")String mode){
  Path file=Path.of(".env.research-scope").toAbsolutePath();String chosen=null;
  try{
   if(Files.isSymbolicLink(file))throw new IllegalStateException("Unsafe research identity");
   if(Files.isRegularFile(file)&&Files.size(file)<200){chosen=Files.readString(file).strip();if(!chosen.matches("[a-f0-9]{64}"))throw new IllegalStateException("연구 작업공간 식별 확인 필요");}
   if(chosen==null){
    var env=ExecutionBrokerClient.Environment.valueOf(mode);
    if(broker.configured(env))chosen=broker.accountKey(env); // Local fingerprint only; no provider request.
    else{var old=jdbc.queryForList("SELECT DISTINCT scope_id FROM committee_ai_job LIMIT 2",String.class);if(old.size()==1)chosen=old.getFirst();}
    if(chosen==null)chosen=PublicInformationService.hash(UUID.randomUUID().toString());
    Files.writeString(file,chosen,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
   }else Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
  }catch(Exception failure){throw new IllegalStateException("비공개 연구 작업공간 식별을 준비하지 못했어요.");}
  id=chosen;
 }
 public String id(){return id;}
 @Override public String toString(){return "ResearchWorkspace[REDACTED]";}
}
