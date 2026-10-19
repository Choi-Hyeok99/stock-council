#!/usr/bin/env python3
"""Local-only Git publication guard. Reports locations/categories, never matching values."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
PUBLIC_DOCS = {'docs/WORKING-MANUAL.md', 'docs/ARCHITECTURE.md', 'docs/VALUE-REVIEW.md', 'docs/SECURITY-REVIEW.md', 'docs/LEARNING-ROADMAP.md'}
ROOT_FILES = {'.gitignore', '.gitattributes', 'AGENTS.md', 'README.md', 'SECURITY.md', 'build.gradle', 'settings.gradle', 'compose.yaml', 'gradlew', 'gradlew.bat'}
PATTERNS = {
    'credential_token': re.compile(r'\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})'),
    'discord_webhook': re.compile(r'https://(?:canary\.|ptb\.)?discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]+'),
    'private_key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'personal_email': re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'),
    'personal_home_path': re.compile(r'/' + r'Users/[^/\s]+/'),
    'private_tailnet_address': re.compile(r'\b[a-z0-9-]+\.tail[a-z0-9]+\.ts\.net\b', re.I),
    'credential_in_url': re.compile(r'\b(?:https?|postgres(?:ql)?|mysql|redis)://[^\s/@]+:[^\s/@]+@', re.I),
    'basic_authorization': re.compile(r'\bBasic\s+[A-Za-z0-9+/]{12,}={0,2}\b'),
}

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, stderr=subprocess.DEVNULL)

def allowed(path):
    p = Path(path)
    if p.is_absolute() or '..' in p.parts or any(x.startswith('.env') for x in p.parts): return False
    if any(x in {'private', 'build', 'backups', 'logs', 'screenshots', '.idea', '.vscode', '.gradle', '.ssh', '.aws', 'out', 'target'} for x in p.parts): return False
    if p.name in {'.netrc', '.npmrc', '.pypirc', '.git-credentials'}: return False
    if p.suffix.lower() in {'.log', '.db', '.sqlite', '.sqlite3', '.dump', '.bak', '.backup', '.pem', '.key', '.p8', '.p12', '.pfx', '.hprof', '.jfr', '.png', '.jpg', '.jpeg', '.webp', '.pdf', '.csv', '.tsv', '.xlsx', '.docx', '.zip', '.tar', '.gz'}: return False
    return path in ROOT_FILES or path in PUBLIC_DOCS or (len(p.parts)>1 and p.parts[0] in {'src','scripts','gradle','.githooks','.github'})

def local_secrets():
    values = set()
    for p in ROOT.glob('.env*'):
        if p.is_symlink() or not p.is_file() or p.stat().st_size>16384:
            raise ValueError('Local secret source requires review')
        contents=p.read_text()
        # These identity files contain a raw value, without KEY=value syntax.
        if p.name in {'.env.execution-salt', '.env.research-scope'}:
            value=contents.strip()
            if value: values.add(value)
            continue
        properties={}
        for line in contents.splitlines():
            if line.lstrip().startswith(('#','!')): continue
            if '=' not in line: continue
            key,value=line.split('=',1)
            key=key.strip().upper();value=value.strip();properties[key]=value
            if re.search(r'KEY|SECRET|PASSWORD|WEBHOOK|SALT|ACCOUNT|CANO',key) and len(value)>=6: values.add(value)
        if properties.get('APP_LOGIN_PASSWORD'):
            values.add(properties.get('APP_LOGIN_USERNAME','user')+':'+properties['APP_LOGIN_PASSWORD'])
    # Common accidental copies in URLs, JSON and authorization headers. No value
    # leaves this process; diagnostics only contain a category and source location.
    variants=set(values)
    for value in values:
        encoded=value.encode('utf-8')
        variants.update({quote(value,safe=''),json.dumps(value,ensure_ascii=True)[1:-1],value.replace('/','\\/'),
                         base64.b64encode(encoded).decode(),base64.urlsafe_b64encode(encoded).decode()})
    variants.update(v.rstrip('=') for v in list(variants) if len(v.rstrip('='))>=8)
    return variants

def safe_name(path,values):
    return path if not any(v in path for v in values) and not any(r.search(path) for r in PATTERNS.values()) else '<sensitive filename>'

def check_text(label,data,values,metadata=False):
    failures=[]
    safe=safe_name(label,values)
    try: text=data.decode('utf-8')
    except UnicodeError: return [{'file':safe,'kind':'unreviewed_binary'}]
    for n,line in enumerate(text.splitlines(),1):
        # Commit authors are reviewed separately before the first commit. Secret
        # values in messages/tags are still blocked by the push hook.
        kinds=[kind for kind,rx in PATTERNS.items() if rx.search(line)
               and not (metadata and kind=='personal_email' and line.startswith(('author ','committer ')))]
        if any(v in line for v in values): kinds.append('actual_local_secret')
        for kind in kinds: failures.append({'file':safe,'line':n,'kind':kind})
    return failures

def check(path,data,values):
    safe=safe_name(path,values)
    if not allowed(path): return [{'file':safe,'kind':'private_or_unreviewed_path'}]
    if len(data)>1024*1024: return [{'file':safe,'kind':'oversized_file_requires_review'}]
    if path=='gradle/wrapper/gradle-wrapper.jar':
        expected=(ROOT/'gradle/wrapper/gradle-wrapper.jar.sha256').read_text().strip()
        return [] if re.fullmatch('[0-9a-f]{64}',expected) and hashlib.sha256(data).hexdigest()==expected else [{'file':safe,'kind':'wrapper_checksum_mismatch'}]
    return check_text(path,data,values)

def run(staged=False,history=False,candidates=False):
    values=local_secrets();failures=[];count=0
    if staged:
        paths=git('ls-files','-z').decode().split('\0')
        for path in filter(None,paths):
            mode=git('ls-files','--stage','--',path).decode().split()[0]
            if mode=='120000': failures.append({'file':safe_name(path,values),'kind':'symlink'});continue
            failures+=check(path,git('show',':'+path),values);count+=1
    if candidates or not (staged or history):
        paths=git('ls-files','-co','--exclude-standard','-z').decode().split('\0')
        for path in sorted(set(filter(None,paths))):
            p=ROOT/path
            if p.is_symlink():failures.append({'file':safe_name(path,values),'kind':'symlink'});continue
            if not p.is_file():continue
            if p.stat().st_size>1024*1024:failures.append({'file':safe_name(path,values),'kind':'oversized_file_requires_review'});continue
            failures+=check(path,p.read_bytes(),values);count+=1
    if history:
        entries=git('rev-list','--objects','--all').decode().splitlines()
        if len(entries)>50000:failures.append({'kind':'history_too_large_requires_review'})
        else:
            for row in entries:
                parts=row.split(' ',1)
                oid=parts[0];kind=git('cat-file','-t',oid).strip()
                if kind in (b'commit',b'tag'):
                    if int(git('cat-file','-s',oid))>1024*1024:failures.append({'kind':'oversized_history_metadata'});continue
                    failures+=check_text('<history '+kind.decode()+'>',git('cat-file',kind.decode(),oid),values,metadata=True);count+=1
                    continue
                if len(parts)!=2:continue
                oid,path=parts
                if kind!=b'blob':continue
                if int(git('cat-file','-s',oid))>1024*1024:failures.append({'file':safe_name(path,values),'kind':'oversized_history_blob'});continue
                failures+=check(path,git('cat-file','blob',oid),values);count+=1
    return {'checkedFiles':count,'blocked':bool(failures),'findings':failures}

def main():
    p=argparse.ArgumentParser();p.add_argument('--staged',action='store_true');p.add_argument('--history',action='store_true');p.add_argument('--candidates',action='store_true');args=p.parse_args()
    try: result=run(**vars(args))
    except Exception:print('Repository check failed; no secret values were printed.');return 1
    print(json.dumps(result,ensure_ascii=False));return 1 if result['blocked'] else 0

if __name__=='__main__':sys.exit(main())
