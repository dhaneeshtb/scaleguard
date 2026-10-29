// h2 test backend (go run go/h2backend.go :9100 [cert.pem key.pem for TLS]): plain HTTP ("/" and "/echo") plus a
// (5-byte length-prefixed messages, grpc-status trailers) over h2c and, optionally, TLS h2.
package main

import (
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

func readMsg(r io.Reader) ([]byte, error) {
	var h [5]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return nil, err
	}
	b := make([]byte, binary.BigEndian.Uint32(h[1:]))
	_, err := io.ReadFull(r, b)
	return b, err
}

func writeMsg(w io.Writer, b []byte) {
	var h [5]byte
	binary.BigEndian.PutUint32(h[1:], uint32(len(b)))
	w.Write(h[:])
	w.Write(b)
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
}

func grpc(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/grpc")
	w.Header().Set("Trailer", "Grpc-Status, Grpc-Message")
	w.Header().Set("X-Backend-Proto", r.Proto)
	w.Header().Set("X-Seen-Xff", r.Header.Get("X-Forwarded-For"))
	w.Header().Set("X-Seen-Authority", r.Host)
	switch r.URL.Path {
	case "/test.Echo/Unary":
		m, err := readMsg(r.Body)
		if err != nil {
			w.Header().Set("Grpc-Status", "3")
			return
		}
		writeMsg(w, append([]byte("echo:"), m...))
	case "/test.Echo/ServerStream":
		m, _ := readMsg(r.Body)
		for i := 0; i < 5; i++ {
			writeMsg(w, []byte(fmt.Sprintf("%s-%d", m, i)))
		}
	case "/test.Echo/ClientStream":
		var parts []string
		for {
			m, err := readMsg(r.Body)
			if err != nil {
				break
			}
			parts = append(parts, string(m))
		}
		writeMsg(w, []byte(strings.Join(parts, ",")))
	case "/test.Echo/Bidi":
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		for {
			m, err := readMsg(r.Body)
			if err != nil {
				break
			}
			writeMsg(w, append([]byte("bidi:"), m...))
		}
	case "/test.Echo/Big":
		m, _ := readMsg(r.Body)
		writeMsg(w, make([]byte, 4<<20)) // 4 MiB response
		_ = m
	case "/test.Echo/Fail":
		readMsg(r.Body)
		w.Header().Set("Grpc-Status", "5")
		w.Header().Set("Grpc-Message", "not found here")
		return
	default:
		w.Header().Set("Grpc-Status", "12")
		return
	}
	w.Header().Set("Grpc-Status", "0")
}

func main() {
	addr := ":9100"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.Header.Get("Content-Type"), "application/grpc") {
			grpc(w, r)
			return
		}
		w.Header().Set("X-Backend-Proto", r.Proto)
		if r.URL.Path == "/echo" {
			b, _ := io.ReadAll(r.Body)
			fmt.Fprintf(w, "proto=%s host=%s xff=%s xfp=%s len=%d\n", r.Proto, r.Host, r.Header.Get("X-Forwarded-For"), r.Header.Get("X-Forwarded-Proto"), len(b))
			return
		}
		w.Write([]byte("hello, world\n"))
	})
	s := &http.Server{Addr: addr, Handler: mux}
	s.Protocols = new(http.Protocols)
	s.Protocols.SetHTTP1(true)
	s.Protocols.SetUnencryptedHTTP2(true)
	if len(os.Args) > 3 { // TLS mode: addr cert key
		s.Protocols.SetHTTP2(true)
		s.TLSConfig = &tls.Config{NextProtos: []string{"h2", "http/1.1"}}
		panic(s.ListenAndServeTLS(os.Args[2], os.Args[3]))
	}
	panic(s.ListenAndServe())
}
