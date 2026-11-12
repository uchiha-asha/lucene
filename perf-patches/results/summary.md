
## p4-postings  (base vs p4)   checksums: 18 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, gap=100000, source=postings | 4.404 us/op | 0.452 | 3.2% | 1.922 | 0.075 | 1.7% | -56.3% |
| collectDocIds | deletedPercent=0, gap=10000, source=postings | 26.10 us/op | 1.884 | 10.3% | 3.865 | 0.203 | 7.5% | -85.2% |
| collectDocIds | deletedPercent=0, gap=1000, source=postings | 140.8 us/op | 10.42 | 4.9% | 15.76 | 0.974 | 7.0% | -88.8% |
| collectDocIds | deletedPercent=0, gap=256, source=postings | 266.1 us/op | 33.52 | 10.4% | 59.49 | 3.216 | 3.7% | -77.6% |
| collectDocIds | deletedPercent=0, gap=100, source=postings | 550.5 us/op | 31.75 | 3.7% | 533.9 | 23.17 | 5.5% | -3.0% |
| collectDocIds | deletedPercent=0, gap=50, source=postings | 828.3 us/op | 58.84 | 1.1% | 740.2 | 23.98 | 1.5% | -10.6% |
| collectDocValues | deletedPercent=0, gap=100000, source=postings | 4.586 us/op | 0.424 | 11.1% | 2.259 | 0.081 | 3.2% | -50.7% |
| collectDocValues | deletedPercent=0, gap=10000, source=postings | 27.69 us/op | 3.757 | 5.0% | 5.587 | 0.228 | 1.0% | -79.8% |
| collectDocValues | deletedPercent=0, gap=1000, source=postings | 149.2 us/op | 8.202 | 5.2% | 33.71 | 3.659 | 8.7% | -77.4% |
| collectDocValues | deletedPercent=0, gap=256, source=postings | 424.6 us/op | 33.75 | 9.2% | 160.7 | 27.39 | 11.4% | -62.2% |
| collectDocValues | deletedPercent=0, gap=100, source=postings | 966.0 us/op | 44.02 | 5.0% | 993.9 | 120.3 | 4.2% | +2.9% |
| collectDocValues | deletedPercent=0, gap=50, source=postings | 1403 us/op | 110.3 | 2.7% | 1329 | 63.81 | 2.8% | -5.3% |
| count | deletedPercent=0, gap=100000, source=postings | 2.760 us/op | 0.123 | 2.5% | 1.876 | 0.091 | 3.4% | -32.0% |
| count | deletedPercent=0, gap=10000, source=postings | 11.09 us/op | 0.637 | 3.9% | 3.911 | 0.315 | 16.1% | -64.7% |
| count | deletedPercent=0, gap=1000, source=postings | 34.37 us/op | 3.397 | 13.1% | 14.35 | 1.195 | 9.7% | -58.2% |
| count | deletedPercent=0, gap=256, source=postings | 57.12 us/op | 4.538 | 11.9% | 51.93 | 4.272 | 7.4% | -9.1% |
| count | deletedPercent=0, gap=100, source=postings | 98.06 us/op | 8.496 | 1.4% | 96.33 | 3.941 | 2.7% | -1.8% |
| count | deletedPercent=0, gap=50, source=postings | 162.0 us/op | 14.35 | 5.0% | 165.8 | 12.22 | 3.7% | +2.4% |

## p4-points  (base vs p4)   checksums: 9 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, gap=10000, source=points | 42.25 us/op | 3.148 | 6.2% | 10.51 | 0.515 | 6.9% | -75.1% |
| collectDocIds | deletedPercent=0, gap=1000, source=points | 168.3 us/op | 12.99 | 13.8% | 47.49 | 2.422 | 7.8% | -71.8% |
| collectDocIds | deletedPercent=0, gap=100, source=points | 696.6 us/op | 47.81 | 9.1% | 706.0 | 37.83 | 3.6% | +1.4% |
| collectDocValues | deletedPercent=0, gap=10000, source=points | 45.50 us/op | 3.587 | 8.8% | 12.77 | 0.731 | 1.4% | -71.9% |
| collectDocValues | deletedPercent=0, gap=1000, source=points | 196.9 us/op | 10.63 | 4.3% | 60.88 | 3.636 | 1.8% | -69.1% |
| collectDocValues | deletedPercent=0, gap=100, source=points | 1109 us/op | 33.90 | 2.1% | 1145 | 79.64 | 6.9% | +3.2% |
| count | deletedPercent=0, gap=10000, source=points | 17.21 us/op | 0.705 | 1.2% | 11.59 | 1.601 | 7.6% | -32.6% |
| count | deletedPercent=0, gap=1000, source=points | 69.68 us/op | 4.547 | 9.6% | 49.18 | 3.591 | 10.7% | -29.4% |
| count | deletedPercent=0, gap=100, source=points | 219.5 us/op | 13.92 | 7.8% | 237.5 | 18.73 | 5.9% | +8.2% |

## p4-cluster  (base vs p4)   checksums: 3 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, gap=50, source=cluster | 129.8 us/op | 8.619 | 6.3% | 126.6 | 7.699 | 9.3% | -2.4% |
| collectDocValues | deletedPercent=0, gap=50, source=cluster | 449.4 us/op | 79.04 | 24.1% | 473.8 | 80.66 | 11.7% | +5.4% |
| count | deletedPercent=0, gap=50, source=cluster | 8.702 us/op | 1.107 | 10.8% | 8.847 | 0.913 | 15.7% | +1.7% |

## p4-deletes  (base vs p4)   checksums: 9 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=5, gap=10000, source=postings | 42.40 us/op | 2.714 | 7.6% | 4.513 | 0.680 | 13.3% | -89.4% |
| collectDocIds | deletedPercent=5, gap=256, source=postings | 292.9 us/op | 23.74 | 2.6% | 69.94 | 7.866 | 17.5% | -76.1% |
| collectDocIds | deletedPercent=5, gap=50, source=postings | 816.7 us/op | 88.44 | 11.5% | 843.1 | 63.88 | 4.9% | +3.2% |
| collectDocValues | deletedPercent=5, gap=10000, source=postings | 47.79 us/op | 7.221 | 16.0% | 6.251 | 0.434 | 5.5% | -86.9% |
| collectDocValues | deletedPercent=5, gap=256, source=postings | 673.1 us/op | 156.6 | 18.5% | 284.3 | 53.20 | 22.3% | -57.8% |
| collectDocValues | deletedPercent=5, gap=50, source=postings | 1476 us/op | 125.3 | 9.2% | 1385 | 80.79 | 2.6% | -6.1% |
| count | deletedPercent=5, gap=10000, source=postings | 30.23 us/op | 4.233 | 12.8% | 3.854 | 0.276 | 8.8% | -87.2% |
| count | deletedPercent=5, gap=256, source=postings | 109.6 us/op | 8.486 | 3.4% | 70.30 | 4.160 | 10.9% | -35.9% |
| count | deletedPercent=5, gap=50, source=postings | 235.0 us/op | 39.16 | 22.4% | 210.9 | 18.41 | 8.6% | -10.3% |

## p2-postings  (base vs p2)   checksums: 8 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| denseTermFilter | numSegments=1 | 1128 us/op | 61.64 | 3.6% | 1169 | 36.81 | 1.3% | +3.7% |
| denseTermFilter | numSegments=20 | 1204 us/op | 25.04 | 0.9% | 1191 | 75.19 | 3.9% | -1.1% |
| disjunctionOf1024Ids | numSegments=1 | 4969 us/op | 189.8 | 6.2% | 4307 | 197.7 | 6.4% | -13.3% |
| disjunctionOf1024Ids | numSegments=20 | 40401 us/op | 1959 | 2.1% | 35970 | 1591 | 3.5% | -11.0% |
| disjunctionOf64Ids | numSegments=1 | 225.9 us/op | 10.47 | 4.4% | 189.3 | 9.010 | 6.0% | -16.2% |
| disjunctionOf64Ids | numSegments=20 | 2190 us/op | 71.70 | 2.2% | 1938 | 79.64 | 1.8% | -11.5% |
| sortByKeyword | numSegments=1 | 1529 us/op | 71.05 | 0.9% | 740.7 | 54.94 | 9.8% | -51.6% |
| sortByKeyword | numSegments=20 | 1218 us/op | 77.03 | 7.9% | 826.0 | 56.16 | 10.2% | -32.2% |

## p2-gc  (base vs p2)   checksums: 8 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| denseTermFilter | numSegments=1 | 1155 us/op | 333.5 | nan% | 1125 | 318.7 | nan% | -2.6% |
| denseTermFilter | numSegments=1, gc.alloc.rate.norm | 11497 B/op | | | 10390 B/op | | | -9.6% |
| denseTermFilter | numSegments=20 | 1296 us/op | 293.8 | nan% | 1170 | 248.3 | nan% | -9.7% |
| denseTermFilter | numSegments=20, gc.alloc.rate.norm | 212376 B/op | | | 191537 B/op | | | -9.8% |
| disjunctionOf1024Ids | numSegments=1 | 4997 us/op | 1250 | nan% | 4212 | 586.4 | nan% | -15.7% |
| disjunctionOf1024Ids | numSegments=1, gc.alloc.rate.norm | 9703273 B/op | | | 7571790 B/op | | | -22.0% |
| disjunctionOf1024Ids | numSegments=20 | 42937 us/op | 12974 | nan% | 34046 | 1264 | nan% | -20.7% |
| disjunctionOf1024Ids | numSegments=20, gc.alloc.rate.norm | 120148566 B/op | | | 102639458 B/op | | | -14.6% |
| disjunctionOf64Ids | numSegments=1 | 236.2 us/op | 33.22 | nan% | 190.2 | 64.48 | nan% | -19.5% |
| disjunctionOf64Ids | numSegments=1, gc.alloc.rate.norm | 606997 B/op | | | 472305 B/op | | | -22.2% |
| disjunctionOf64Ids | numSegments=20 | 2485 us/op | 610.2 | nan% | 2051 | 481.8 | nan% | -17.4% |
| disjunctionOf64Ids | numSegments=20, gc.alloc.rate.norm | 7595548 B/op | | | 6501499 B/op | | | -14.4% |
| sortByKeyword | numSegments=1 | 1471 us/op | 313.6 | nan% | 753.9 | 160.7 | nan% | -48.7% |
| sortByKeyword | numSegments=1, gc.alloc.rate.norm | 3683063 B/op | | | 1594602 B/op | | | -56.7% |
| sortByKeyword | numSegments=20 | 1210 us/op | 231.0 | nan% | 864.9 | 192.9 | nan% | -28.5% |
| sortByKeyword | numSegments=20, gc.alloc.rate.norm | 2066282 B/op | | | 1046390 B/op | | | -49.4% |

## p1-collect  (base vs p1)   checksums: 8 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sortedNumericCollect | matchPercent=100 | 71.35 ms/op | 6.314 | 13.8% | 64.39 | 2.928 | 5.5% | -9.8% |
| sortedNumericCollect | matchPercent=50 | 37.16 ms/op | 1.462 | 5.7% | 40.27 | 2.468 | 6.9% | +8.4% |
| sortedNumericCollect | matchPercent=10 | 8.266 ms/op | 0.512 | 7.6% | 8.268 | 0.649 | 8.2% | +0.0% |
| sortedNumericCollect | matchPercent=1 | 1.660 ms/op | 0.129 | 2.5% | 1.726 | 0.101 | 4.5% | +4.0% |
| sortedSetCollect | matchPercent=100 | 76.06 ms/op | 5.155 | 7.6% | 64.09 | 4.307 | 3.8% | -15.7% |
| sortedSetCollect | matchPercent=50 | 39.91 ms/op | 3.476 | 3.0% | 38.48 | 1.101 | 3.2% | -3.6% |
| sortedSetCollect | matchPercent=10 | 8.392 ms/op | 0.420 | 8.2% | 7.975 | 0.324 | 0.7% | -5.0% |
| sortedSetCollect | matchPercent=1 | 1.467 ms/op | 0.256 | 16.3% | 1.350 | 0.069 | 1.1% | -8.0% |

## p1-nextdoc  (base vs p1)   checksums: 2 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| sortedNumericNextDoc | matchPercent=100 | 62.45 ms/op | 2.812 | 2.2% | 54.54 | 2.611 | 5.4% | -12.7% |
| sortedSetNextDoc | matchPercent=100 | 61.35 ms/op | 4.377 | 7.1% | 51.79 | 3.544 | 4.0% | -15.6% |

## p3-1m  (base vs p3)   checksums: 4 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, maxDoc=1000000, numHits=100 | 13.81 us/op | 0.876 | 5.4% | 12.92 | 0.886 | 1.6% | -6.4% |
| search | field=timestamp, maxDoc=1000000, numHits=3000 | 65.20 us/op | 4.186 | 7.1% | 53.69 | 2.508 | 6.8% | -17.6% |
| search | field=random, maxDoc=1000000, numHits=100 | 20.37 us/op | 1.800 | 10.6% | 20.17 | 1.246 | 3.7% | -1.0% |
| search | field=random, maxDoc=1000000, numHits=3000 | 82.35 us/op | 11.59 | 17.3% | 81.27 | 6.797 | 9.3% | -1.3% |

## p3-20m  (base vs p3)   checksums: 6 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, maxDoc=20000000, numHits=100 | 14.78 us/op | 0.626 | 2.8% | 13.95 | 0.777 | 3.7% | -5.6% |
| search | field=timestamp, maxDoc=20000000, numHits=5000 | 103.5 us/op | 7.003 | 8.1% | 74.22 | 4.018 | 2.4% | -28.3% |
| search | field=timestamp, maxDoc=20000000, numHits=50000 | 1086 us/op | 46.06 | 3.0% | 784.5 | 31.53 | 2.4% | -27.7% |
| search | field=random, maxDoc=20000000, numHits=100 | 24.33 us/op | 1.178 | 4.2% | 25.28 | 1.937 | 2.0% | +3.9% |
| search | field=random, maxDoc=20000000, numHits=5000 | 392.7 us/op | 13.55 | 0.9% | 390.3 | 22.87 | 8.9% | -0.6% |
| search | field=random, maxDoc=20000000, numHits=50000 | 1647 us/op | 83.86 | 4.4% | 1619 | 135.1 | 9.8% | -1.7% |

## p3-on-p4  (p4 vs p34)   checksums: 6 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| search | field=timestamp, maxDoc=20000000, numHits=100 | 15.02 us/op | 0.935 | 3.4% | 14.15 | 0.953 | 5.1% | -5.8% |
| search | field=timestamp, maxDoc=20000000, numHits=5000 | 104.8 us/op | 5.611 | 0.8% | 74.16 | 8.649 | 6.0% | -29.2% |
| search | field=timestamp, maxDoc=20000000, numHits=50000 | 1050 us/op | 75.65 | 7.5% | 768.1 | 41.09 | 4.3% | -26.8% |
| search | field=random, maxDoc=20000000, numHits=100 | 14.36 us/op | 0.671 | 3.3% | 15.01 | 1.005 | 9.2% | +4.5% |
| search | field=random, maxDoc=20000000, numHits=5000 | 82.01 us/op | 5.667 | 7.5% | 87.47 | 6.685 | 3.9% | +6.7% |
| search | field=random, maxDoc=20000000, numHits=50000 | 644.3 us/op | 43.44 | 3.1% | 633.9 | 53.96 | 6.5% | -1.6% |

## p5-existing  (base vs p5)   checksums: 0 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| scoreIntoBitSet | density=0.1, maxDoc=1000000, withDeletions=false | 40.05 ops/ms | 2.827 | 7.6% | 45.11 | 3.167 | 8.7% | +12.6% |
| scoreIntoBitSet | density=0.1, maxDoc=1000000, withDeletions=true | 27.41 ops/ms | 1.399 | 3.5% | 29.20 | 1.827 | 10.0% | +6.5% |
| scoreIntoBitSet | density=0.5, maxDoc=1000000, withDeletions=false | 43.30 ops/ms | 2.510 | 6.3% | 52.76 | 2.629 | 4.3% | +21.8% |
| scoreIntoBitSet | density=0.5, maxDoc=1000000, withDeletions=true | 31.13 ops/ms | 2.669 | 11.7% | 38.99 | 1.905 | 7.2% | +25.2% |

## p5-cached  (base vs p5)   checksums: 4 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, percent=10, query=cached | 1384 us/op | 47.81 | 2.6% | 1409 | 38.52 | 6.3% | +1.8% |
| collectDocIds | deletedPercent=0, percent=50, query=cached | 4139 us/op | 125.4 | 6.1% | 4162 | 184.5 | 2.9% | +0.6% |
| count | deletedPercent=0, percent=10, query=cached | 105.4 us/op | 6.274 | 6.0% | 90.50 | 5.681 | 8.9% | -14.1% |
| count | deletedPercent=0, percent=50, query=cached | 108.6 us/op | 6.792 | 13.1% | 98.60 | 7.952 | 13.6% | -9.2% |

## p5-search  (base vs p5)   checksums: 16 identical, 0 different
| benchmark | params | baseline | ± | fork spread | patched | ± | fork spread | change |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| collectDocIds | deletedPercent=0, percent=10, query=single | 1985 us/op | 82.44 | 5.8% | 2084 | 112.3 | 4.9% | +5.0% |
| collectDocIds | deletedPercent=0, percent=10, query=conjunction | 2183 us/op | 203.7 | 3.8% | 2089 | 164.6 | 4.8% | -4.3% |
| collectDocIds | deletedPercent=0, percent=50, query=single | 4523 us/op | 328.5 | 3.9% | 4440 | 317.8 | 12.7% | -1.8% |
| collectDocIds | deletedPercent=0, percent=50, query=conjunction | 3367 us/op | 215.7 | 1.4% | 3371 | 177.3 | 5.2% | +0.1% |
| collectDocIds | deletedPercent=5, percent=10, query=single | 2034 us/op | 130.1 | 4.5% | 1969 | 148.8 | 3.4% | -3.2% |
| collectDocIds | deletedPercent=5, percent=10, query=conjunction | 2213 us/op | 141.9 | 9.1% | 2112 | 125.1 | 8.1% | -4.5% |
| collectDocIds | deletedPercent=5, percent=50, query=single | 4653 us/op | 321.2 | 4.6% | 4514 | 176.7 | 2.4% | -3.0% |
| collectDocIds | deletedPercent=5, percent=50, query=conjunction | 3393 us/op | 243.4 | 8.9% | 3312 | 353.6 | 5.9% | -2.4% |
| count | deletedPercent=0, percent=10, query=single | 668.6 us/op | 41.17 | 2.3% | 634.5 | 58.71 | 11.4% | -5.1% |
| count | deletedPercent=0, percent=10, query=conjunction | 1172 us/op | 101.0 | 9.7% | 1167 | 78.77 | 9.8% | -0.4% |
| count | deletedPercent=0, percent=50, query=single | 442.0 us/op | 28.78 | 9.6% | 395.3 | 41.69 | 16.3% | -10.6% |
| count | deletedPercent=0, percent=50, query=conjunction | 985.3 us/op | 77.87 | 4.8% | 916.9 | 69.55 | 10.4% | -6.9% |
| count | deletedPercent=5, percent=10, query=single | 727.4 us/op | 49.88 | 10.4% | 760.9 | 107.7 | 11.4% | +4.6% |
| count | deletedPercent=5, percent=10, query=conjunction | 1256 us/op | 74.84 | 9.3% | 1219 | 121.0 | 10.7% | -2.9% |
| count | deletedPercent=5, percent=50, query=single | 488.1 us/op | 29.41 | 5.7% | 467.6 | 24.58 | 3.1% | -4.2% |
| count | deletedPercent=5, percent=50, query=conjunction | 1030 us/op | 78.50 | 9.7% | 964.5 | 77.74 | 7.2% | -6.4% |
