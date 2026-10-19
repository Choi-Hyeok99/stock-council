#!/usr/bin/env python3
"""Owner HTTP route checks. Reads status/JSON only; no browser rendering or mutation."""
import base64,json,sys
from pathlib import Path
from urllib.request import Request,build_opener,HTTPRedirectHandler
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parent.parent
class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args):return None
def main():
    cfg=dict(line.split('=',1)for line in(ROOT/'.env.login').read_text().splitlines()if'='in line and not line.startswith('#'))
    auth='Basic '+base64.b64encode((cfg.get('APP_LOGIN_USERNAME','user')+':'+cfg['APP_LOGIN_PASSWORD']).encode()).decode()
    opener=build_opener(NoRedirect());checks=[]
    def request(path,method):
        try:
            with opener.open(Request('http://127.0.0.1:8090'+path,headers={'Authorization':auth},method=method),timeout=20)as r:
                is_json='application/json'in r.headers.get('Content-Type','')
                if method=='GET'and is_json:json.loads(r.read(3000000))
                return r.status,is_json
        except HTTPError as e:return e.code,False
    pages=['/orders','/holdings','/sell','/committee','/committee/reviews','/ideas','/financials','/theses','/overview','/ai-costs','/research','/philosophy']
    pages+=['/guide/'+s for s in ['security','manual','value','architecture','committee','berkshire','financials','value-research','calendar','weekend','maintenance']]
    for path in pages:
        code,_=request(path,'HEAD');checks.append({'path':path,'passed':code==200})
    for path in ['/api/orders','/api/manual-sales','/api/manual-sales/timings','/api/research-status','/api/committee','/api/committee/reviews','/api/financials','/api/theses','/api/ideas','/api/market-session','/api/market-data','/api/market-indices','/api/universe','/api/ai-costs','/api/research','/api/reports','/api/philosophy','/api/strategy-observations','/api/disclosure-impacts','/api/discord-preview']:
        code,is_json=request(path,'GET');checks.append({'path':path,'passed':code==200 and is_json})
    for path in ['/js/committee.js','/js/holdings.js','/js/overview.js','/js/philosophy.js','/js/theses.js','/js/guide.js','/js/site-navigation.js','/css/committee.css']:
        code,_=request(path,'HEAD');checks.append({'path':path,'passed':code==200})
    result={'passed':sum(x['passed']for x in checks),'total':len(checks),'checks':checks,'scope':'HTTP 상태·JSON 형식. 실제 화면·주문·유료 AI·발송 검증 아님.'}
    out=ROOT/'build/checks/routes-readonly.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2));out.chmod(0o600)
    print(f"읽기 전용 경로 확인: {result['passed']}/{result['total']} 통과")
    for x in checks:
        if not x['passed']:print('확인 필요: '+x['path'])
    return 0 if result['passed']==result['total']else 1
if __name__=='__main__':
    try:sys.exit(main())
    except Exception:print('로컬 경로 응답 확인 필요 · 비밀값/응답 원문 미출력');sys.exit(1)
