import os,sys
port=int(sys.argv[1]); inodes=set()
for f in [x for x in ("/proc/net/tcp","/proc/net/tcp6") if os.path.exists(x)]:
    for l in open(f).readlines()[1:]:
        p=l.split()
        if int(p[1].split(':')[1],16)==port and p[3]=='0A': inodes.add(p[9])
for pid in os.listdir('/proc'):
    if not pid.isdigit(): continue
    try:
        for fd in os.listdir(f'/proc/{pid}/fd'):
            l=os.readlink(f'/proc/{pid}/fd/{fd}')
            if l.startswith('socket:[') and l[8:-1] in inodes: print(pid); sys.exit()
    except Exception: pass
