// The Go SDK behind the same GET /flags and GET /flush routes as node-server.ts.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	flagbearer "github.com/flagbearer-dev/sdks/go"
)

func main() {
	key := os.Getenv("FLAGBEARER_SERVER_KEY")
	if key == "" {
		log.Fatal("The integration fixture requires FLAGBEARER_SERVER_KEY.")
	}
	address := os.Getenv("FLAGBEARER_URL")
	if address == "" {
		address = flagbearer.DefaultURL
	}

	// One client for the whole server, not one per HTTP request.
	flags, err := flagbearer.New(key, flagbearer.WithURL(address), flagbearer.WithDebug(true))
	if err != nil {
		log.Fatal(err)
	}
	if err := flags.Sync(context.Background()); err != nil {
		fmt.Println("Initial sync failed; serving defaults while the SDK recovers.", err)
	} else {
		fmt.Println("Initial Flagbearer ruleset loaded.")
	}

	reply := func(w http.ResponseWriter, status int, body any) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(body)
	}
	mux := http.NewServeMux()
	// Uploads pending exposures now, so a test sees whether the server took them.
	mux.HandleFunc("GET /flush", func(w http.ResponseWriter, r *http.Request) {
		if err := flags.Flush(r.Context()); err != nil {
			reply(w, 502, map[string]string{"error": err.Error()})
			return
		}
		reply(w, 200, map[string]bool{"flushed": true})
	})
	mux.HandleFunc("GET /flags", func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		flag := query.Get("flag")
		fallback := query.Get("default")
		if fallback == "" {
			fallback = "false"
		}
		if flag == "" || (fallback != "true" && fallback != "false") {
			reply(w, 400, map[string]string{"error": "Provide flag, and optionally default=true or default=false."})
			return
		}
		user := flagbearer.User{Key: query.Get("userKey"), Email: query.Get("email")}
		// Local evaluation: this request does not call Flagbearer's API.
		enabled := flags.IsEnabled(flag, user, fallback == "true")
		reply(w, 200, map[string]any{"flag": flag, "user": user, "enabled": enabled})
	})

	listener, err := net.Listen("tcp", "127.0.0.1:"+envOr("PORT", "0"))
	if err != nil {
		log.Fatal(err)
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-stop
		_ = flags.Close()
		os.Exit(0)
	}()
	fmt.Printf("Go SDK fixture listening on http://%s\n", listener.Addr())
	log.Fatal(http.Serve(listener, mux))
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
