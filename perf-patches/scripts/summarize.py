#!/usr/bin/env python3
"""Summarize A/B JMH results: results/<label>.base.json vs results/<label>.<patch>.json.

For each benchmark+params prints the baseline and patched mean, the half-width of JMH's 99.9%
confidence interval, the largest relative spread between the means of forks (fresh JVMs), the
relative change, and whether the checksums printed by the benchmark's teardown are identical.
"""
import glob, json, os, re, sys

BENCH = os.environ.get('BENCH', os.path.expanduser('~/lucene-bench'))
R = os.path.join(BENCH, 'results')

def load(path):
    out = {}
    for b in json.load(open(path)):
        name = b['benchmark'].split('.')[-1]
        params = b.get('params') or {}
        key = (name, tuple(sorted(params.items())))
        pm = b['primaryMetric']
        forks = [sum(f) / len(f) for f in pm['rawData']]
        mean = pm['score']
        spread = (max(forks) - min(forks)) / mean * 100 if len(forks) > 1 else float('nan')
        sec = {k: v['score'] for k, v in (b.get('secondaryMetrics') or {}).items()}
        out[key] = dict(mean=mean, err=pm['scoreError'], unit=pm['scoreUnit'], mode=b['mode'],
                        spread=spread, forks=len(forks), sec=sec)
    return out

def checksums(path):
    out = {}
    if not os.path.exists(path):
        return out
    bench = None
    for line in open(path, errors='replace'):
        m = re.match(r'# Benchmark: .*\.(\w+)$', line.strip())
        if m:
            bench = m.group(1)
        for m in re.finditer(r'checksum\(([^)]*)\)=(-?\d+)', line):
            out.setdefault((bench, m.group(1)), set()).add(m.group(2))
    return out

def fmt(x):
    if x >= 1000: return '%.0f' % x
    if x >= 100: return '%.1f' % x
    if x >= 10: return '%.2f' % x
    return '%.3f' % x

labels = sys.argv[1:] or sorted({os.path.basename(p).split('.')[0] for p in glob.glob(R + '/*.json')})
for label in labels:
    variants = sorted((os.path.basename(p).split('.')[1] for p in glob.glob('%s/%s.*.json' % (R, label))),
                      key=lambda v: (v != 'base', len(v), v))
    if len(variants) != 2:
        continue
    # the reference is "base" if there is one, else the variant with the shorter name (p4 vs p34)
    ref, variant = variants
    base, pat = load('%s/%s.%s.json' % (R, label, ref)), load('%s/%s.%s.json' % (R, label, variant))
    cb, cp = checksums('%s/%s.%s.log' % (R, label, ref)), checksums('%s/%s.%s.log' % (R, label, variant))
    same = sum(1 for k in cb if k in cp and cb[k] == cp[k] and len(cb[k]) == 1)
    diff = [k for k in cb if k in cp and (cb[k] != cp[k] or len(cb[k]) != 1)]
    print('\n## %s  (%s vs %s)   checksums: %d identical, %d different%s' % (
        label, ref, variant, same, len(diff), (' ' + str(diff)) if diff else ''))
    print('| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |')
    print('|---|---|---:|---:|---:|---:|---:|---:|---:|')
    for key in base:
        if key not in pat: continue
        b, p = base[key], pat[key]
        change = (p['mean'] - b['mean']) / b['mean'] * 100
        params = ', '.join('%s=%s' % kv for kv in key[1])
        print('| %s | %s | %s %s | %s | %.1f%% | %s | %s | %.1f%% | %+.1f%% |' % (
            key[0], params, fmt(b['mean']), b['unit'], fmt(b['err']), b['spread'],
            fmt(p['mean']), fmt(p['err']), p['spread'], change))
        for metric in ('gc.alloc.rate.norm',):
            if metric in b['sec'] and metric in p['sec']:
                print('| %s | %s, %s | %s B/op | | | %s B/op | | | %+.1f%% |' % (
                    key[0], params, metric, fmt(b['sec'][metric]), fmt(p['sec'][metric]),
                    (p['sec'][metric] - b['sec'][metric]) / b['sec'][metric] * 100))
