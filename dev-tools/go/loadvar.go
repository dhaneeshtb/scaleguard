// Realistic-ish load: browser headers, a per-request random session cookie, varying paths/queries.
// Usage: loadvar <base-url> <concurrency> <seconds>
package main

import (
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

var words = []string{"running", "shoes", "red", "jacket", "kids", "winter", "sale", "leather", "boots", "size", "10", "wool", "hat", "gift", "for", "dad", "blue", "denim", "summer", "dress"}

func path(r *rand.Rand) string {
	switch r.Intn(4) {
	case 0:
		return fmt.Sprintf("/search?q=%s+%s&page=%d", words[r.Intn(len(words))], words[r.Intn(len(words))], 1+r.Intn(5))
	case 1:
		return fmt.Sprintf("/products/sku-%d", r.Intn(100000))
	case 2:
		return fmt.Sprintf("/orders?status=open&customer=%d&sort=-created", r.Intn(50000))
	default:
		return "/"
	}
}

func main() {
	base, conc, secs := os.Args[1], 50, 10
	if len(os.Args) > 2 { conc, _ = strconv.Atoi(os.Args[2]) }
	if len(os.Args) > 3 { secs, _ = strconv.Atoi(os.Args[3]) }
	tr := &http.Transport{MaxIdleConnsPerHost: conc * 2}
	c := &http.Client{Transport: tr, Timeout: 10 * time.Second}
	var ok, bad int64
	var mu sync.Mutex
	var lat []float64
	stop := time.Now().Add(time.Duration(secs) * time.Second)
	var wg sync.WaitGroup
	for i := 0; i < conc; i++ {
		wg.Add(1)
		go func(seed int64) {
			defer wg.Done()
			r := rand.New(rand.NewSource(seed))
			var local []float64
			for time.Now().Before(stop) {
				req, _ := http.NewRequest("GET", base+path(r), nil)
				req.Header.Set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15")
				req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
				req.Header.Set("Accept-Language", "en-IN,en;q=0.9")
				req.Header.Set("Cookie", fmt.Sprintf("session=%x; theme=dark; cart=%d", r.Uint64(), r.Intn(9)))
				t0 := time.Now()
				resp, err := c.Do(req)
				if err != nil { atomic.AddInt64(&bad, 1); continue }
				io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				if resp.StatusCode == 200 { atomic.AddInt64(&ok, 1) } else { atomic.AddInt64(&bad, 1) }
				local = append(local, float64(time.Since(t0).Microseconds())/1000)
			}
			mu.Lock(); lat = append(lat, local...); mu.Unlock()
		}(int64(i))
	}
	wg.Wait()
	sort.Float64s(lat)
	p := func(q float64) float64 { if len(lat) == 0 { return 0 }; return lat[int(q*float64(len(lat)-1))] }
	fmt.Printf("c=%d %8.0f req/s  ok=%d non200=%d  p50=%.2fms p99=%.2fms\n", conc, float64(ok)/float64(secs), ok, bad, p(0.5), p(0.99))
}
