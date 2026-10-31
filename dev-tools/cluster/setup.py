import os
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ct.py')).read().split('A,B=')[0])
import time
t = login(7181)
req(7181, "POST", "/config/hostgroups?scaleguard=true", {"groupId": "p1", "scheme": "http", "host": "127.0.0.1", "port": "9200"}, t)
req(7181, "POST", "/config/targetsystems?scaleguard=true", {"groupId": "tg1", "name": "T1", "scheme": "http", "basePath": "/", "hostGroupId": "p1"}, t)
req(7181, "POST", "/config/sourcesystems?scaleguard=true", {"name": "Svc", "host": "svc.test", "port": "7190", "scheme": "http", "basePath": "/", "target": "tg1"}, t)
print(req(7181, "POST", "/ratelimit?scaleguard=true", {"enabled": True, "perMinute": 1000000, "burstSeconds": 10, "clusterMode": "shared"}, t)[0])
