// Sends requests as fast as possible from C connections for D seconds; prints status counts per 10s window.
package main

import ("fmt"; "io"; "net/http"; "os"; "strconv"; "sync"; "sync/atomic"; "time")

func main() {
	url := os.Args[1]; c, _ := strconv.Atoi(os.Args[2]); d, _ := strconv.Atoi(os.Args[3])
	xff := ""; if len(os.Args) > 4 { xff = os.Args[4] }
	tr := &http.Transport{MaxIdleConnsPerHost: c, MaxConnsPerHost: c}
	cl := &http.Client{Transport: tr}
	var ok, limited, other int64
	var retryAfter atomic.Value; retryAfter.Store("")
	start := time.Now(); end := start.Add(time.Duration(d) * time.Second)
	var wg sync.WaitGroup
	for i := 0; i < c; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done(); n := 0
			for time.Now().Before(end) {
				req, _ := http.NewRequest("GET", url, nil)
				if xff == "random" { n++; req.Header.Set("X-Forwarded-For", fmt.Sprintf("203.0.%d.%d", i, n%250)) } else if xff != "" { req.Header.Set("X-Forwarded-For", xff) }
				r, err := cl.Do(req)
				if err != nil { atomic.AddInt64(&other, 1); continue }
				io.Copy(io.Discard, r.Body); r.Body.Close()
				switch r.StatusCode { case 200: atomic.AddInt64(&ok, 1); case 429: atomic.AddInt64(&limited, 1); retryAfter.Store(r.Header.Get("Retry-After")); default: atomic.AddInt64(&other, 1) }
			}
		}(i)
	}
	go func() {
		var lastOk int64
		for t := 10; t <= d; t += 10 {
			time.Sleep(time.Until(start.Add(time.Duration(t) * time.Second)))
			o := atomic.LoadInt64(&ok); fmt.Printf("  t=%3ds  allowed in window=%5d  (total allowed=%d, 429s=%d)\n", t, o-lastOk, o, atomic.LoadInt64(&limited)); lastOk = o
		}
	}()
	wg.Wait()
	fmt.Printf("TOTAL allowed=%d 429=%d other=%d  Retry-After=%q\n", ok, limited, other, retryAfter.Load())
}
