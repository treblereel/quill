#!/usr/bin/env python3
"""Local native query comparison. Output is explicit; no project results are saved by default."""
from __future__ import annotations
import argparse, hashlib, json, math, os, statistics, time
from pathlib import Path
from quill_workflow_benchmark import WorkflowClient

def semantic(value):
    if isinstance(value, dict):
        return {key:semantic(item) for key,item in value.items() if key not in {'_meta','generation'}}
    if isinstance(value,list):return [semantic(item) for item in value]
    return value

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--project',type=Path,required=True)
    parser.add_argument('--target',required=True)
    parser.add_argument('--method',required=True)
    parser.add_argument('--descriptor',required=True)
    parser.add_argument('--samples',type=int,default=30)
    parser.add_argument('--warmup',type=int,default=5)
    parser.add_argument('--limits',default='1,10,20,100')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.samples<10 or args.warmup<1:parser.error('Use at least 10 samples and one warmup')
    os.environ['QUILL_QUERY_METRICS']='1'
    result={'binary_sha256':hashlib.sha256(args.binary.read_bytes()).hexdigest(),'samples':args.samples,'pages':{}}
    with WorkflowClient([str(args.binary.resolve())],args.project.resolve(),180) as client:
        client.call('get_overview',{})
        for limit in map(int,args.limits.split(',')):
            arguments={'target':args.target,'name':args.method,'kind':'method','signature':args.descriptor,'limit':limit}
            timings=[];hashes=[]
            for i in range(args.warmup+args.samples):
                answer,trace=client.call('find_symbol_usages',arguments)
                if answer.get('error_code'):raise RuntimeError(answer)
                if i>=args.warmup:
                    timings.append(trace['latency_ms'])
                    hashes.append(hashlib.sha256(json.dumps(semantic(answer),sort_keys=True).encode()).hexdigest())
            result['pages'][str(limit)]={'median_ms':statistics.median(timings),'p95_ms':sorted(timings)[math.ceil(len(timings)*.95)-1],
                'min_ms':min(timings),'max_ms':max(timings),'response_sha256':sorted(set(hashes)),'total':answer.get('total'),'latency_ms':timings}
        time.sleep(.05)
        result['metrics']=[json.loads(line) for line in client.client.stderr if line.startswith('{"event":"quill_query_metrics"')]
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'pages':{k:{a:b for a,b in v.items() if a not in {'latency_ms','response_sha256'}} for k,v in result['pages'].items()},'metric_samples':len(result['metrics'])},indent=2))
if __name__=='__main__':main()
