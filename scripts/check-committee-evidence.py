#!/usr/bin/env python3
"""Read owner JSON evidence coverage. Never starts a model, order, or outbound message."""
import argparse,base64,json,os,re,sys
from pathlib import Path
from urllib.request import Request,urlopen
ROOT=Path(__file__).resolve().parent.parent
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--code');parser.add_argument('--require-excerpts',action='store_true');args=parser.parse_args()
    if args.code and not re.fullmatch(r'[0-9]{6}',args.code):raise ValueError()
    cfg=dict(line.split('=',1)for line in(ROOT/'.env.login').read_text().splitlines()if'='in line and not line.startswith('#'))
    auth='Basic '+base64.b64encode((cfg.get('APP_LOGIN_USERNAME','user')+':'+cfg['APP_LOGIN_PASSWORD']).encode()).decode()
    suffix='?code='+args.code+'&agenda=BUY_REVIEW'if args.code else''
    with urlopen(Request('http://127.0.0.1:8090/api/committee'+suffix,headers={'Authorization':auth}),timeout=20)as response:view=json.load(response)
    r=view['entry']['report'];evidence=r['evidence'];ids={e['id']for e in evidence};coverage=view.get('coverage',[]);b=r.get('businessEvidence')or{};passages=b.get('passages',[])
    checks=[]
    def check(label,passed):checks.append({'name':label,'passed':bool(passed)})
    check('최신 자료 전달 기준',r['version']=='COMMITTEE_RESEARCH_PACK_V8')
    check('8개 역할의 입력 범위',len(coverage)==8 and len({x['key']for x in coverage})==8)
    check('역할 근거가 실제 원본에 연결',all(set(c['evidenceIds'])<=ids for c in coverage))
    check('빈 공식 링크 없음',all(e.get('url')for e in evidence if e['accessLevel']=='OFFICIAL_LINK'))
    check('발췌가 원본·기간·수집시각에 연결',all(p['sourceId']in ids and p['period']and p.get('fetchedAt')and re.fullmatch(r'https://dart\.fss\.or\.kr/dsaf001/main\.do\?rcpNo=[0-9]{14}',p['sourceUrl'])for p in passages))
    check('본문 입력 상한',sum(len(p['text'])for p in passages)<=3000)
    check('본문 또는 공백 명시',bool(passages)or bool(b.get('gaps')))
    check('대화 검증 오류 없음',not view['conversation']['issues'])
    check('자동 AI 꺼짐',not view['aiStatus']['automaticReviewsEnabled'])
    check('월 예산 유지',float(view['aiStatus']['budget']['ceilingUsd'])==10)
    if args.require_excerpts:check('실제 본문 발췌 준비',bool(passages))
    report={'passed':sum(x['passed']for x in checks),'total':len(checks),'checks':checks,'scope':'입력 연결/범위 확인. AI 출력 정확성·전체 공시 검증·브라우저 렌더링 검증 아님.'}
    out=ROOT/'build/checks/committee-evidence.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2));out.chmod(0o600)
    print(f"위원회 근거 확인: {report['passed']}/{report['total']} 통과")
    for x in checks:
        if not x['passed']:print('확인 필요: '+x['name'])
    return 0 if report['passed']==report['total']else 1
if __name__=='__main__':
    try:sys.exit(main())
    except Exception:print('근거 상태/로컬 서버 확인 필요 · 비밀값/응답 원문 미출력');sys.exit(1)
