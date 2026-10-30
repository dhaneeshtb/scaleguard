import os,sys
root=sys.argv[1]; kids={}
for p in os.listdir('/proc'):
    if p.isdigit():
        try: kids.setdefault(open(f'/proc/{p}/stat').read().rsplit(')',1)[1].split()[1],[]).append(p)
        except Exception: pass
todo=[root]; ticks=0; rss=0
while todo:
    p=todo.pop(); todo+=kids.get(p,[])
    try:
        f=open(f'/proc/{p}/stat').read().rsplit(')',1)[1].split(); ticks+=int(f[11])+int(f[12])
        rss+=int([l for l in open(f'/proc/{p}/status') if l.startswith('VmRSS')][0].split()[1])
    except Exception: pass
print(ticks if sys.argv[2]=='ticks' else rss//1024)
