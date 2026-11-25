#!/usr/bin/env python3
import re, sys, statistics
# Usage: tools/perf/summ.py .local/perf/<label>.txt ...  -> one table row per file
for f in sys.argv[1:]:
    t = open(f).read()
    ms = [int(x) for x in re.findall(r'Displayed dev\.vulcanlauncher/\.MainActivity.*\+(\d+)ms', t)]
    snaps = {}
    for name, body in re.findall(r'== meminfo: (.*?) ==\n(.*?)(?=\n==|\Z)', t, re.S):
        g = lambda k: (re.search(r'%s:\s+(\d+)' % k, body) or [None, '?'])[1]
        snaps[name] = '/'.join(g(k) for k in ['Java Heap', 'Native Heap', 'Code', 'Graphics', 'TOTAL PSS'])
    fs = lambda k: (re.search(k, t) or [None, '?'])[1]
    frames = '%s, %s, %s/%s/%s/%s' % (fs(r'Total frames rendered: (\d+)'), fs(r'Janky frames: \d+ \(([\d.]+%)\)'),
        *[fs(r'\n *%sth percentile: (\d+)ms' % p) for p in (50, 90, 95, 99)])
    lbl = re.search(r'label: (\S+)', t)[1]
    print('|', lbl, '|', statistics.median(ms) if ms else '?', ms, '|',
          ' | '.join(snaps.get(k, '-') for k in ['idle home #2', 'drawer open, not scrolled', 'after drawer', 'in background']),
          '|', frames, '|')
