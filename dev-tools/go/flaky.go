// Backend that answers "<name>" normally, or 503 while <flagfile> exists. go run go/flaky.go :9201 A /tmp/a.flag
package main

import ("net/http"; "os")

func main() {
	addr, name, flag := os.Args[1], os.Args[2], os.Args[3]
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if _, err := os.Stat(flag); err == nil {
			w.WriteHeader(503)
			w.Write([]byte("down " + name + "\n"))
			return
		}
		w.Write([]byte(name + "\n"))
	})
	panic(http.ListenAndServe(addr, nil))
}
