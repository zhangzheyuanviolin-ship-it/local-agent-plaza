#!/usr/bin/env python3
"""Validate Qwen3.5-4B LiteRT-LM bundle before publishing."""
from __future__ import annotations
import argparse, importlib.metadata, json, tempfile, time
from pathlib import Path

def load_config(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))

def extract_text(message) -> str:
    if isinstance(message, str): return message
    if isinstance(message, dict):
        content=message.get("content")
        if isinstance(content,str): return content
        if isinstance(content,list):
            out=[]
            for item in content:
                if isinstance(item,str): out.append(item)
                elif isinstance(item,dict) and isinstance(item.get("text"),str): out.append(item["text"])
            return "".join(out)
        if isinstance(message.get("text"),str): return message["text"]
    return str(message)

def verify_magic(bundle: Path) -> str:
    with bundle.open("rb") as f: magic=f.read(8)
    if magic!=b"LITERTLM": raise RuntimeError(f"Magic gate failed: {magic!r}")
    return magic.decode("ascii")

def _parse_executor_metadata(path: Path):
    from google.protobuf import text_format
    from litert_lm_builder.runtime.proto import executor_metadata_pb2
    metadata=executor_metadata_pb2.ExecutorMetadata()
    if path.suffix.lower() in {".pbtext",".textproto",".txt"}:
        text_format.Parse(path.read_text(encoding="utf-8"),metadata)
    else:
        metadata.ParseFromString(path.read_bytes())
    return metadata

def verify_archive_and_executor_metadata(bundle: Path, expected_cache_length: int) -> dict:
    import litert_lm_builder
    from litert_lm_builder.runtime.proto import executor_metadata_pb2
    with tempfile.TemporaryDirectory(prefix="qwen35-4b-litert-unpack-") as td:
        root=Path(td)
        litert_lm_builder.unpack(str(bundle),str(root))
        files=sorted(p for p in root.rglob("*") if p.is_file() and ("executormetadata" in p.name.lower().replace("_","") or "executor_metadata" in p.name.lower()))
        if not files: raise RuntimeError("Executor metadata missing")
        metadata=None; parsed=None; last=None
        for p in files:
            try:
                c=_parse_executor_metadata(p)
                if c.HasField("llm_executor_metadata"): metadata=c; parsed=p; break
            except Exception as exc: last=exc
        if metadata is None: raise RuntimeError(f"Executor metadata parse failed: {last}")
        buffers=list(metadata.llm_executor_metadata.state_buffers)
        names=[]; types=[]; linear=[]; full_k=[]; full_v=[]
        for b in buffers:
            n=b.prefill_input_name or b.decode_input_name; names.append(n)
            try: types.append(executor_metadata_pb2.StateBuffer.Type.Name(b.type))
            except Exception: types.append(str(b.type))
            if n.startswith(("kv_cache_c_","kv_cache_r_")): linear.append(b)
            elif n.startswith("kv_cache_k_"): full_k.append(b)
            elif n.startswith("kv_cache_v_"): full_v.append(b)
        for prefix in ("kv_cache_c_","kv_cache_r_","kv_cache_k_","kv_cache_v_"):
            if not any(n.startswith(prefix) for n in names): raise RuntimeError(f"Missing state prefix {prefix}")
        # Official Qwen3.5-4B config: 32 layers = 24 linear-attention + 8 full-attention.
        if len(linear)!=48 or len(full_k)!=8 or len(full_v)!=8:
            raise RuntimeError(f"Hybrid-state count gate failed: linear={len(linear)}, full_k={len(full_k)}, full_v={len(full_v)}")
        bad=[b.prefill_input_name or b.decode_input_name for b in linear if b.type!=executor_metadata_pb2.StateBuffer.TYPE_LINEAR_ATTENTION or not b.HasField("sequence_axis") or b.sequence_axis!=0]
        if bad: raise RuntimeError(f"Linear state metadata gate failed: {bad}")
        bad=[]
        for b in full_k:
            n=b.prefill_input_name or b.decode_input_name
            if b.type!=executor_metadata_pb2.StateBuffer.TYPE_GLOBAL_KEY_CACHE or not b.HasField("sequence_axis") or b.sequence_axis!=2 or not b.HasField("maximum_sequence_length") or b.maximum_sequence_length!=expected_cache_length: bad.append(n)
        if bad: raise RuntimeError(f"Full-attention K metadata gate failed: {bad}")
        bad=[]
        for b in full_v:
            n=b.prefill_input_name or b.decode_input_name
            if b.type!=executor_metadata_pb2.StateBuffer.TYPE_GLOBAL_VALUE_CACHE or not b.HasField("sequence_axis") or b.sequence_axis!=3 or not b.HasField("maximum_sequence_length") or b.maximum_sequence_length!=expected_cache_length: bad.append(n)
        if bad: raise RuntimeError(f"Full-attention V metadata gate failed: {bad}")
        return {
            "executor_metadata_file":parsed.name,"state_buffer_count":len(buffers),
            "linear_recurrent_state_buffer_count":len(linear),"full_attention_k_state_buffer_count":len(full_k),
            "full_attention_v_state_buffer_count":len(full_v),"full_attention_kv_state_buffer_count":len(full_k)+len(full_v),
            "state_type_names":sorted(set(types)),"state_name_sample":names[:32],
            "sequence_axis_zero_all_linear_states":True,"full_attention_k_sequence_axis":2,
            "full_attention_v_sequence_axis":3,"full_attention_maximum_sequence_length":expected_cache_length,"status":"PASS"}

def runtime_smoke(bundle: Path,cfg: dict)->dict:
    import litert_lm
    requested=int(cfg.get("smoke_max_num_tokens",cfg["cache_length"])); prompt=str(cfg.get("smoke_prompt","你好")); min_chars=int(cfg.get("smoke_min_output_chars",1))
    sampler={"top_k":20,"top_p":0.8,"temperature":0.6}
    t0=time.time()
    with litert_lm.Engine(str(bundle),max_num_tokens=requested) as engine:
        t1=time.time()
        with engine.create_conversation(sampler_config=litert_lm.SamplerConfig(**sampler)) as conv:
            t2=time.time(); msg=conv.send_message(prompt); t3=time.time()
    text=extract_text(msg).strip()
    if len(text)<min_chars: raise RuntimeError(f"Runtime smoke output too short: {text!r}")
    try: rv=importlib.metadata.version("litert-lm-api-nightly")
    except importlib.metadata.PackageNotFoundError:
        try: rv=importlib.metadata.version("litert-lm-api")
        except importlib.metadata.PackageNotFoundError: rv="unknown"
    return {"runtime_package_version":rv,"max_num_tokens":requested,"sampler_override":sampler,"engine_create_seconds":round(t1-t0,3),"conversation_create_seconds":round(t2-t1,3),"send_message_seconds":round(t3-t2,3),"output_chars":len(text),"output_preview":text[:500]}

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--config",required=True,type=Path); ap.add_argument("--bundle",required=True,type=Path); ap.add_argument("--manifest",required=True,type=Path); a=ap.parse_args()
    cfg=load_config(a.config); bundle=a.bundle.resolve()
    if not bundle.is_file(): raise FileNotFoundError(bundle)
    print("=== Gate 1: LiteRT-LM magic ==="); magic=verify_magic(bundle); print("magic="+magic)
    print("=== Gate 2/3: Qwen3.5-4B hybrid executor metadata ==="); state=verify_archive_and_executor_metadata(bundle,int(cfg["cache_length"])); print(json.dumps(state,ensure_ascii=False,indent=2))
    print("=== Gate 4/5: LiteRT-LM 0.15 CPU Engine generation ==="); smoke=runtime_smoke(bundle,cfg); print(json.dumps(smoke,ensure_ascii=False,indent=2))
    m=json.loads(a.manifest.read_text(encoding="utf-8")); m["verification"]={"magic":magic,"hybrid_executor_state":state,"runtime_smoke":smoke,"status":"PASS"}; a.manifest.write_text(json.dumps(m,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    (a.manifest.parent/"runtime_smoke_output.txt").write_text(smoke["output_preview"]+"\n",encoding="utf-8")
    print("ALL VALIDATION GATES PASSED")
if __name__=="__main__": main()
