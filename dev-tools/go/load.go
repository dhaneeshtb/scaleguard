// Keep-alive HTTP load generator: C connections, D seconds, reports RPS and latency percentiles.
package main

import (
	"fmt"; "io"; "net/http"; "os"; "sort"; "strconv"; "sync"; "sync/atomic"; "time"
)

func main() {
	url := os.Args[1]; c, _ := strconv.Atoi(os.Args[2]); d, _ := strconv.Atoi(os.Args[3])
	tr := &http.Transport{MaxIdleConnsPerHost: c, MaxConnsPerHost: c, DisableCompression: true}
	client := &http.Client{Transport: tr, Timeout: 10 * time.Second}
	var ok, bad int64
	lat := make([][]time.Duration, c)
	end := time.Now().Add(time.Duration(d) * time.Second)
	var wg sync.WaitGroup
	for i := 0; i < c; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for time.Now().Before(end) {
				t0 := time.Now()
				resp, err := client.Get(url)
				if err != nil { atomic.AddInt64(&bad, 1); continue }
				io.Copy(io.Discard, resp.Body); resp.Body.Close()
				if resp.StatusCode == 200 { atomic.AddInt64(&ok, 1); lat[i] = append(lat[i], time.Since(t0)) } else { atomic.AddInt64(&bad, 1) }
			}
		}(i)
	}
	start := time.Now(); wg.Wait(); el := time.Since(start).Seconds()
	var all []time.Duration
	for _, l := range lat { all = append(all, l...) }
	sort.Slice(all, func(a, b int) bool { return all[a] < all[b] })
	p := func(q float64) time.Duration { if len(all) == 0 { return 0 }; return all[int(float64(len(all)-1)*q)] }
	fmt.Printf("c=%-4d %8.0f req/s  ok=%d errors=%d  p50=%v p99=%v\n", c, float64(ok)/el, ok, bad, p(0.5).Round(time.Microsecond), p(0.99).Round(time.Microsecond))
}
