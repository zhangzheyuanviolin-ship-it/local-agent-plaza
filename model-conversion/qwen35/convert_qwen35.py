#!/usr/bin/env python3
"""Convert an official Qwen3.5 checkpoint into a standard LiteRT-LM bundle."""
from __future__ import annotations
import argparse, hashlib, importlib, inspect, json, os, platform, shutil, sys, time
from pathlib import Path

def sha256_file(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        while True:
            b=f.read(chunk_size)
            if not b: break
            h.update(b)
    return h.hexdigest()

def load_config(path: Path) -> dict:
    cfg=json.loads(path.read_text(encoding='utf-8'))
    required={'model_id','quantization_recipe','cache_length','prefill_lengths','output_filename'}
    missing=sorted(required-set(cfg))
    if missing: raise ValueError(f'Missing config keys: {missing}')
    return cfg

def environment_preflight(model_id: str, cache_length: int) -> dict:
    import torch, transformers, litert_torch
    from transformers import AutoConfig
    from litert_torch.generative.export_hf.model_ext import metadata_builder
    qe=importlib.import_module('litert_torch.generative.export_hf.model_ext.qwen3_5.exportable_module')
    qs=importlib.import_module('litert_torch.generative.export_hf.model_ext.qwen3_5.modeling_qwen3_5_static')
    for attr in ('LiteRTExportableModuleForQwen3_5Prefill','LiteRTExportableModuleForQwen3_5Generate'):
        if not hasattr(qe, attr): raise RuntimeError(f'Installed litert-torch lacks {attr}')
    if not hasattr(qs,'Qwen3_5StaticGatedDeltaNet'):
        raise RuntimeError('Installed litert-torch lacks Qwen3.5 GatedDeltaNet reauthoring')
    eb=getattr(metadata_builder,'build_executor_metadata',None)
    if eb is None: raise RuntimeError('Executor metadata builder unavailable')
    src=inspect.getsource(eb)
    for token in ('TYPE_LINEAR_ATTENTION','kv_cache_c_','kv_cache_r_','kv_cache_k_','kv_cache_v_'):
        if token not in src: raise RuntimeError(f'Executor metadata missing {token}')
    hf=AutoConfig.from_pretrained(model_id)
    mt=getattr(hf,'model_type',''); text=getattr(hf,'text_config',hf)
    if mt!='qwen3_5' and getattr(text,'model_type','')!='qwen3_5':
        raise RuntimeError(f'Expected Qwen3.5 config, got {mt!r}/{getattr(text,"model_type",None)!r}')
    maxpos=int(getattr(text,'max_position_embeddings',0) or 0)
    if maxpos and cache_length>maxpos: raise RuntimeError(f'cache_length={cache_length} > max_position_embeddings={maxpos}')
    layers=list(getattr(text,'layer_types',[]) or [])
    if 'linear_attention' not in layers or 'full_attention' not in layers:
        raise RuntimeError('Qwen3.5 hybrid layer_types missing')
    return {'python':sys.version,'platform':platform.platform(),'torch':getattr(torch,'__version__','unknown'),'transformers':getattr(transformers,'__version__','unknown'),'litert_torch':getattr(litert_torch,'__version__','unknown'),'model_type':mt,'max_position_embeddings':maxpos,'num_hidden_layers':int(getattr(text,'num_hidden_layers',0) or 0),'linear_attention_layers':sum(x=='linear_attention' for x in layers),'full_attention_layers':sum(x=='full_attention' for x in layers),'executor_metadata':'linear+recurrent+kv-state-aware'}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--config',required=True,type=Path); p.add_argument('--output-dir',required=True,type=Path); p.add_argument('--lightweight',choices=('true','false'),default='true'); a=p.parse_args()
    cfg=load_config(a.config); out=a.output_dir.resolve(); out.mkdir(parents=True,exist_ok=True)
    for item in list(out.iterdir()): shutil.rmtree(item) if item.is_dir() else item.unlink()
    pre=environment_preflight(str(cfg['model_id']),int(cfg['cache_length']))
    print('=== Qwen3.5 conversion preflight ==='); print(json.dumps(pre,ensure_ascii=False,indent=2))
    from litert_torch.generative.export_hf import export as exp
    light=a.lightweight=='true'; start=time.time()
    exp.export(model=str(cfg['model_id']),output_dir=str(out),task='text_generation',keep_temporary_files=False,prefill_lengths=[int(x) for x in cfg['prefill_lengths']],cache_length=int(cfg['cache_length']),quantization_recipe=str(cfg['quantization_recipe']),externalize_embedder=bool(cfg.get('externalize_embedder',True)),single_token_embedder=bool(cfg.get('single_token_embedder',True)),cache_implementation='LiteRTLMCache',k_ts_idx=2,v_ts_idx=3,use_jinja_template=bool(cfg.get('use_jinja_template',True)),bundle_litert_lm=True,export_vision_encoder=False,export_audio_encoder=False,experimental_lightweight_conversion=light,sampler_top_k=int(cfg.get('sampler_top_k',1)),sampler_top_p=float(cfg.get('sampler_top_p',1.0)),sampler_temperature=float(cfg.get('sampler_temperature',0.0)))
    generated=out/'model.litertlm'
    if not generated.is_file():
        c=sorted(out.glob('*.litertlm'))
        if len(c)!=1: raise RuntimeError(f'Expected one .litertlm, found {[x.name for x in c]}')
        generated=c[0]
    final=out/str(cfg['output_filename'])
    if generated!=final: generated.replace(final)
    magic=final.open('rb').read(8)
    if magic!=b'LITERTLM': raise RuntimeError(f'Invalid LiteRT-LM magic: {magic!r}')
    digest=sha256_file(final); final.with_suffix(final.suffix+'.sha256').write_text(f'{digest}  {final.name}\n',encoding='utf-8')
    manifest={'schema':'qwen35-litert-conversion-result.v1','source_config':cfg,'environment':pre,'experimental_lightweight_conversion':light,'elapsed_seconds':round(time.time()-start,3),'output_file':final.name,'output_bytes':final.stat().st_size,'sha256':digest,'magic_ascii':'LITERTLM','github_sha':os.getenv('GITHUB_SHA',''),'github_run_id':os.getenv('GITHUB_RUN_ID','')}
    (out/'conversion_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('=== Conversion complete ==='); print(json.dumps(manifest,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
