// HTTP/2 + gRPC conformance and load client for ScaleGuard. Usage: go run go/h2check.go <base-url> [tls|x] [waf|expect=<status>[:n]|bench=<conc>:<secs>]
package main

import (
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var fails int

func check(name string, ok bool, detail string) {
	s := "PASS"
	if !ok {
		s = "FAIL"
		fails++
	}
	fmt.Printf("%s  %-38s %s\n", s, name, detail)
}

func frame(b []byte) []byte {
	h := make([]byte, 5, 5+len(b))
	binary.BigEndian.PutUint32(h[1:], uint32(len(b)))
	return append(h, b...)
}

func readMsg(r io.Reader) ([]byte, error) {
	var h [5]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return nil, err
	}
	b := make([]byte, binary.BigEndian.Uint32(h[1:]))
	_, err := io.ReadFull(r, b)
	return b, err
}

func status(resp *http.Response) string {
	if s := resp.Trailer.Get("Grpc-Status"); s != "" {
		return s
	}
	return resp.Header.Get("Grpc-Status") // trailers-only
}

func main() {
	base := os.Args[1]
	useTLS := len(os.Args) > 2 && os.Args[2] == "tls"
	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: "localhost"}}
	tr.Protocols = new(http.Protocols)
	if useTLS {
		tr.Protocols.SetHTTP2(true)
	} else {
		tr.Protocols.SetUnencryptedHTTP2(true)
	}
	c := &http.Client{Transport: tr, Timeout: 20 * time.Second}

	single := func(method string) (*http.Response, error) {
		req, _ := http.NewRequest("POST", base+"/test.Echo/"+method, bytes.NewReader(frame([]byte("x"))))
		req.Header.Set("Content-Type", "application/grpc")
		resp, err := c.Do(req)
		if err == nil {
			io.ReadAll(resp.Body)
			resp.Body.Close()
		}
		return resp, err
	}
	_ = single
	call := func(method string, body []byte) (*http.Response, []byte, error) {
		req, _ := http.NewRequest("POST", base+"/test.Echo/"+method, bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/grpc")
		req.Header.Set("TE", "trailers")
		resp, err := c.Do(req)
		if err != nil {
			return nil, nil, err
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return resp, b, nil
	}

	if len(os.Args) > 3 && strings.HasPrefix(os.Args[3], "bench=") { // bench=<concurrency>:<seconds> unary calls
		var conc, secs int
		fmt.Sscanf(strings.TrimPrefix(os.Args[3], "bench="), "%d:%d", &conc, &secs)
		var okN, bad int64
		stop := time.Now().Add(time.Duration(secs) * time.Second)
		var wg sync.WaitGroup
		for i := 0; i < conc; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for time.Now().Before(stop) {
					if r, e := single("Unary"); e == nil && status(r) == "0" {
						atomic.AddInt64(&okN, 1)
					} else {
						atomic.AddInt64(&bad, 1)
					}
				}
			}()
		}
		wg.Wait()
		fmt.Printf("unary calls/s: %.0f  ok=%d errors=%d\n", float64(okN)/float64(secs), okN, bad)
		return
	}
	if len(os.Args) > 3 && strings.HasPrefix(os.Args[3], "expect=") { // expect=<status>[:n]: n calls, last must have status
		want := strings.TrimPrefix(os.Args[3], "expect=")
		n := 1
		if i := strings.Index(want, ":"); i > 0 {
			fmt.Sscan(want[i+1:], &n)
			want = want[:i]
		}
		var last *http.Response
		var err error
		for i := 0; i < n; i++ {
			last, err = single("Unary")
		}
		if err != nil {
			check("expect grpc-status "+want, false, err.Error())
		} else {
			check("expect grpc-status "+want, status(last) == want, status(last)+" "+last.Header.Get("Grpc-Message"))
		}
		if fails > 0 {
			os.Exit(1)
		}
		return
	}
	// plain HTTP over h2
	if resp, err := c.Get(base + "/echo"); err == nil {
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		check("plain GET over h2", resp.StatusCode == 200 && resp.ProtoMajor == 2, fmt.Sprintf("%s %d %q", resp.Proto, resp.StatusCode, strings.TrimSpace(string(b))))
	} else {
		check("plain GET over h2", false, err.Error())
	}
	big := bytes.Repeat([]byte("x"), 300_000)
	if resp, err := c.Post(base+"/echo", "text/plain", bytes.NewReader(big)); err == nil {
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		check("plain POST 300 KB over h2", strings.Contains(string(b), "len=300000"), strings.TrimSpace(string(b)))
	} else {
		check("plain POST 300 KB over h2", false, err.Error())
	}

	resp, b, err := call("Unary", frame([]byte("hi")))
	if err != nil {
		check("unary", false, err.Error())
	} else {
		m, _ := readMsg(bytes.NewReader(b))
		check("unary", status(resp) == "0" && string(m) == "echo:hi", fmt.Sprintf("status=%s msg=%q backend=%s xff=%s authority=%s", status(resp), m, resp.Header.Get("X-Backend-Proto"), resp.Header.Get("X-Seen-Xff"), resp.Header.Get("X-Seen-Authority")))
	}

	resp, b, err = call("ServerStream", frame([]byte("s")))
	if err == nil {
		r := bytes.NewReader(b)
		var got []string
		for {
			m, e := readMsg(r)
			if e != nil {
				break
			}
			got = append(got, string(m))
		}
		check("server streaming (5 msgs)", status(resp) == "0" && len(got) == 5, fmt.Sprint(got))
	} else {
		check("server streaming (5 msgs)", false, err.Error())
	}

	var cs []byte
	for _, p := range []string{"a", "b", "c"} {
		cs = append(cs, frame([]byte(p))...)
	}
	resp, b, err = call("ClientStream", cs)
	if err == nil {
		m, _ := readMsg(bytes.NewReader(b))
		check("client streaming", status(resp) == "0" && string(m) == "a,b,c", string(m))
	} else {
		check("client streaming", false, err.Error())
	}

	// bidi: send a message, wait for its echo before sending the next (proves no buffering)
	{
		pr, pw := io.Pipe()
		req, _ := http.NewRequest("POST", base+"/test.Echo/Bidi", pr)
		req.Header.Set("Content-Type", "application/grpc")
		req.Header.Set("TE", "trailers")
		done := make(chan *http.Response, 1)
		go func() {
			r, e := c.Do(req)
			if e != nil {
				fmt.Println("bidi error", e)
				done <- nil
				return
			}
			done <- r
		}()
		pw.Write(frame([]byte("m0")))
		var r *http.Response
		select {
		case r = <-done:
		case <-time.After(5 * time.Second):
		}
		ok := r != nil
		var got []string
		if ok {
			for i := 0; i < 3; i++ {
				m, e := readMsg(r.Body)
				if e != nil {
					ok = false
					break
				}
				got = append(got, string(m))
				if i < 2 {
					pw.Write(frame([]byte(fmt.Sprintf("m%d", i+1))))
				}
			}
			pw.Close()
			io.ReadAll(r.Body)
			r.Body.Close()
		}
		check("bidi ping-pong (3 round trips)", ok && len(got) == 3 && status(r) == "0", fmt.Sprint(got))
	}

	resp, b, err = call("Big", frame([]byte("x")))
	if err == nil {
		m, _ := readMsg(bytes.NewReader(b))
		check("4 MiB response", status(resp) == "0" && len(m) == 4<<20, fmt.Sprint(len(m)))
	} else {
		check("4 MiB response", false, err.Error())
	}

	resp, _, err = call("Fail", frame([]byte("x")))
	if err == nil {
		check("backend error status passes through", status(resp) == "5" && resp.Trailer.Get("Grpc-Message") == "not found here", status(resp)+" "+resp.Trailer.Get("Grpc-Message"))
	} else {
		check("backend error status passes through", false, err.Error())
	}

	// concurrency: 200 parallel unary calls multiplexed on one client connection
	var okN, bad int64
	var wg sync.WaitGroup
	start := time.Now()
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			msg := fmt.Sprintf("c%d", i)
			resp, b, err := call("Unary", frame([]byte(msg)))
			if err == nil && status(resp) == "0" {
				m, _ := readMsg(bytes.NewReader(b))
				if string(m) == "echo:"+msg {
					atomic.AddInt64(&okN, 1)
					return
				}
			}
			atomic.AddInt64(&bad, 1)
		}(i)
	}
	wg.Wait()
	check("200 concurrent unary calls", okN == 200, fmt.Sprintf("ok=%d bad=%d in %v", okN, bad, time.Since(start).Round(time.Millisecond)))

	if len(os.Args) > 3 { // optional extra: a path the WAF should block
		resp, _, err = call("Unary?id=1%20UNION%20SELECT%20password%20FROM%20users", frame([]byte("x")))
		if err == nil {
			check("WAF → PERMISSION_DENIED (7)", status(resp) == "7", status(resp)+" "+resp.Header.Get("Grpc-Message"))
		}
	}
	if fails > 0 {
		os.Exit(1)
	}
}
