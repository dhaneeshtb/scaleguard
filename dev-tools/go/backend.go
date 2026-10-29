// Minimal upstream: fixed 13-byte body, keep-alive.
package main

import ("net/http"; "os")

func main() {
	addr := ":9000"
	if len(os.Args) > 1 { addr = os.Args[1] }
	body := []byte("hello, world\n")
	http.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("xff=" + r.Header.Get("X-Forwarded-For") + " realip=" + r.Header.Get("X-Real-IP") + "\n"))
	})
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.Write(body)
	})
	http.ListenAndServe(addr, nil)
}
