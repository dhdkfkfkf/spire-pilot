import sys
lines=open(sys.argv[1],encoding='utf-8',errors='replace').read().splitlines()
lo,hi=int(sys.argv[2]),int(sys.argv[3])
for ln in lines[lo-1:hi]: print(ln)
