#!/usr/bin/env python3
"""Validate a converted Qwen3.5 LiteRT-LM bundle before publishing it."""
from __future__ import annotations
import argparse, importlib.metadata, json, tempfile, time
from pathlib import Path

def extract_text(message)->str:
    if isinstance(message,str): return message
    if isinstance(message,dict):
        c=message.get('content')
        if isinstance(c,str): return c
        if isinstance(c,list):
            out=[]
            for item in c:
                if isinstance(item,str): out.append(item)
                elif isinstance(item,dict) and isinstance(item.get('text'),str): out.append(item['text'])
            return ''.join(out)
        if isinstance(message.get('text'),str): return message['text']
    return str(message)

def verify_magic(bundle:Path)->str:
    magic=bundle.open('rb').read(8)
    if magic!=b'LITERTLM': raise RuntimeError(f'Magic gate failed: {magic!r}')
    return 'LITERTLM'

def verify_archive(bundle:Path)->dict:
    import litert_lm_builder
    from litert_lm_builder.runtime.proto import executor_metadata_pb2
    with tempfile.TemporaryDirectory(prefix='qwen35-unpack-') as td:
        d=Path(td); litert_lm_builder.LitertLmFileBuilder.unpack(str(bundle),str(d))
        files=[p for p in d.rglob('*executor_metadata*') if p.is_file()]
        if not files: raise RuntimeError('Archive has no executor_metadata section')
        meta=executor_metadata_pb2.ExecutorMetadata(); parsed=None; last=None
        for f in files:
            try:
                meta.ParseFromString(f.read_bytes())
                if meta.HasField('llm_executor_metadata'): parsed=f; break
            except Exception as e: last=e
        if parsed is None: raise RuntimeError(f'Could not parse executor metadata: {last}')
        buffers=list(meta.llm_executor_metadata.state_buffers)
        names=[b.prefill_input_name or b.decode_input_name for b in buffers]
        prefixes=('kv_cache_c_','kv_cache_r_','kv_cache_k_','kv_cache_v_')
        missing=[x for x in prefixes if not any(n.startswith(x) for n in names)]
        if missing: raise RuntimeError(f'Missing Qwen3.5 state prefixes {missing}; sample={names[:30]}')
        return {'executor_metadata_file':parsed.name,'state_buffer_count':len(buffers),'linear_recurrent_state_buffer_count':sum(n.startswith('kv_cache_c_') or n.startswith('kv_cache_r_') for n in names),'full_attention_kv_state_buffer_count':sum(n.startswith('kv_cache_k_') or n.startswith('kv_cache_v_') for n in names),'state_name_sample':names[:30]}

def runtime_smoke(bundle:Path,cfg:dict)->dict:
    import litert_lm
    maxn=int(cfg.get('smoke_max_num_tokens',cfg['cache_length'])); prompt=str(cfg.get('smoke_prompt','你好'))
    t0=time.time()
    with litert_lm.Engine(str(bundle),max_num_tokens=maxn) as engine:
        t1=time.time()
        with engine.create_conversation() as conv:
            t2=time.time(); msg=conv.send_message(prompt); t3=time.time()
    text=extract_text(msg).strip()
    if len(text)<int(cfg.get('smoke_min_output_chars',1)): raise RuntimeError(f'Runtime output too short: {text!r}')
    ver='unknown'
    for pkg in ('litert-lm-api-nightly','litert-lm-api'):
        try: ver=importlib.metadata.version(pkg); break
        except importlib.metadata.PackageNotFoundError: pass
    return {'runtime_package_version':ver,'max_num_tokens':maxn,'engine_create_seconds':round(t1-t0,3),'conversation_create_seconds':round(t2-t1,3),'send_message_seconds':round(t3-t2,3),'output_chars':len(text),'output_preview':text[:500]}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--config',required=True,type=Path); p.add_argument('--bundle',required=True,type=Path); p.add_argument('--manifest',required=True,type=Path); a=p.parse_args()
    cfg=json.loads(a.config.read_text(encoding='utf-8')); bundle=a.bundle.resolve()
    print('=== Gate 1 magic ==='); magic=verify_magic(bundle); print(magic)
    print('=== Gate 2 hybrid executor state ==='); state=verify_archive(bundle); print(json.dumps(state,ensure_ascii=False,indent=2))
    print('=== Gate 3 runtime smoke ==='); smoke=runtime_smoke(bundle,cfg); print(json.dumps(smoke,ensure_ascii=False,indent=2))
    m=json.loads(a.manifest.read_text(encoding='utf-8')); m['verification']={'magic':magic,'hybrid_executor_state':state,'runtime_smoke':smoke,'status':'PASS'}; a.manifest.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (a.manifest.parent/'runtime_smoke_output.txt').write_text(smoke['output_preview']+'\n',encoding='utf-8'); print('ALL VALIDATION GATES PASSED')
if __name__=='__main__': main()
