#!/usr/bin/env python3
"""Owner JSON diagnostics for the research build; no rendered pages, paid AI, orders or outbound messages."""
import base64,json,os,stat,subprocess,sys
from pathlib import Path
from datetime import datetime,timezone
from urllib.request import Request,build_opener,HTTPRedirectHandler
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parent.parent
BASE='http://127.0.0.1:8090'
class NoRedirect(HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):return None

def main():
 p=ROOT/'.env.login'
 if p.is_symlink() or not p.is_file():raise ValueError()
 cfg=dict(x.split('=',1) for x in p.read_text().splitlines() if '=' in x and not x.lstrip().startswith('#'))
 auth='Basic '+base64.b64encode((cfg.get('APP_LOGIN_USERNAME','user')+':'+cfg['APP_LOGIN_PASSWORD']).encode()).decode()
 opener=build_opener(NoRedirect());checks=[];payloads=[];status_view=None;committee=None
 def check(name,condition):checks.append({'name':name,'passed':bool(condition)})
 def request(path,owner=False,method='GET'):
  headers={'Accept':'application/json'}
  if owner:headers['Authorization']=auth
  try:
   with opener.open(Request(BASE+path,headers=headers,method=method,data=b'' if method=='POST' else None),timeout=20)as r:
    data=json.loads(r.read(3000000)) if 'application/json' in r.headers.get('Content-Type','') else None
    return r.status,r.headers,data
  except HTTPError as r:return r.code,r.headers,None
 for path in ['/api/orders','/api/manual-sales','/api/ideas','/api/research-status','/api/committee','/api/committee/reviews','/api/ai-costs','/api/financials']:
  code,headers,_=request(path);check('무인증 차단 '+path,code==401)
  code,headers,data=request(path,True);check('소유자 JSON 응답 '+path,code==200 and data is not None)
  check('캐시 방지 '+path,'no-store' in headers.get('Cache-Control',''))
  check('보안 헤더 '+path,headers.get('X-Content-Type-Options')=='nosniff' and "frame-ancestors 'none'" in headers.get('Content-Security-Policy',''))
  payloads.append(data)
  if path=='/api/research-status':status_view=data
  if path=='/api/committee':committee=data
 for path in ['/api/sell','/api/buy-plan','/api/committee/1/trade-preview']:
  code,_,_=request(path,True);check('폐기 API '+path,code==410)
 # Even a routing regression cannot authorize work without a CSRF token.
 for path in ['/ideas/automation','/orders/automation','/api/committee/1/analyze']:
  code,_,_=request(path,True,'POST');check('CSRF 없는 변경 차단 '+path,code==403)
 secrets=[]
 for p in ROOT.glob('.env*'):
  if not p.is_file() or p.is_symlink() or p.stat().st_size>16384:continue
  check('비공개 설정 권한 '+p.name,stat.S_IMODE(p.stat().st_mode)==0o600)
  for line in p.read_text().splitlines():
   if '=' not in line:continue
   k,v=line.split('=',1)
   if any(x in k.upper()for x in ['KEY','SECRET','PASSWORD','ACCOUNT','WEBHOOK','SALT'])and len(v.strip())>=8:secrets.append(v.strip())
  if p.name=='.env.research-scope':secrets.append(p.read_text().strip())
 check('API 비밀값 미노출',not any(s in json.dumps(payloads,ensure_ascii=False)for s in secrets))
 if status_view:
  check('수동 매매·자동 주문 OFF',status_view['orderExecutionAvailable']and not status_view['automaticTradingEnabled'])
  check('자동 AI OFF',not status_view['automaticAiEnabled'])
  check('DB 응답',status_view['databaseHealthy'])
 if committee:
  check('수동 요청 모드',not committee['aiStatus']['automaticReviewsEnabled'])
  check('AI 새 요청 차단 해소',not committee['aiStatus']['requestBlocked'])
  check('월 예산 유지',float(committee['aiStatus']['budget']['ceilingUsd'])==10)
  check('미확정 보관 비용 유지',float(committee['aiStatus']['budget']['archivedReservedUsd'])>=0 and float(committee['aiStatus']['budget']['committedUsd'])+1e-8>=float(committee['aiStatus']['budget']['settledUsd'])+float(committee['aiStatus']['budget']['archivedReservedUsd']))
 report={'checkedAt':datetime.now(timezone.utc).isoformat(),'passed':sum(x['passed']for x in checks),'total':len(checks),'checks':checks,'scope':'JSON 인증·CSRF·헤더·비공개 설정 권한·연구/예산 경계. 유료 호출·주문·발송·화면 렌더링 검증 아님.'}
 out=ROOT/'build/checks/system-readonly.json';out.parent.mkdir(parents=True,exist_ok=True,mode=0o700);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');out.chmod(0o600)
 print(f"읽기 전용 시스템 확인: {report['passed']}/{report['total']} 통과")
 for c in checks:
  if not c['passed']:print('확인 필요: '+c['name'])
 print(report['scope']);return 0 if report['passed']==report['total']else 1
if __name__=='__main__':
 try:sys.exit(main())
 except Exception:print('시스템 응답 또는 로컬 설정 확인 실패 · 비밀값/응답 원문 미출력');sys.exit(1)
