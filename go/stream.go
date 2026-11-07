package flagbearer

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"mime"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const maxEventLength = 65_536

var errTooLarge = errors.New("Flagbearer stream event too large")

// eventParser reads Server-Sent Events fed in arbitrary pieces, calling
// onVersion for each well-formed version event and ignoring everything else.
type eventParser struct {
	onVersion func(int64)
	pending   []byte // bytes of an incomplete UTF-8 character
	buffer    string
	event     string
	data      strings.Builder
}

func newEventParser(onVersion func(int64)) *eventParser {
	return &eventParser{onVersion: onVersion, event: "message"}
}

func (p *eventParser) feed(chunk []byte) error {
	text := append(p.pending, chunk...)
	// Hold back a character split across reads; replace genuinely invalid bytes.
	cut := len(text)
	for index := len(text) - 1; index >= 0 && index >= len(text)-utf8.UTFMax; index-- {
		if utf8.RuneStart(text[index]) {
			if !utf8.FullRune(text[index:]) {
				cut = index
			}
			break
		}
	}
	p.pending = append([]byte(nil), text[cut:]...)
	p.buffer += strings.ToValidUTF8(string(text[:cut]), "�")

	buffer := p.buffer
	start := 0
	for index := 0; index < len(buffer); index++ {
		char := buffer[index]
		if char != '\r' && char != '\n' {
			continue
		}
		// A CR at the end might be the first half of a CRLF in the next piece.
		if char == '\r' && index == len(buffer)-1 {
			break
		}
		if err := p.line(buffer[start:index]); err != nil {
			return err
		}
		if char == '\r' && buffer[index+1] == '\n' {
			index++
		}
		start = index + 1
	}
	p.buffer = buffer[start:]
	if len(p.buffer) > maxEventLength {
		return errors.New("Flagbearer stream line too large")
	}
	return nil
}

func (p *eventParser) line(line string) error {
	if line == "" {
		if p.event == "version" && p.data.Len() > 0 {
			data := strings.TrimSuffix(p.data.String(), "\n")
			decoder := json.NewDecoder(strings.NewReader(data))
			decoder.UseNumber()
			var value map[string]any
			if decoder.Decode(&value) == nil {
				if version, ok := parseVersion(value["version"]); ok {
					p.onVersion(version)
				}
			}
		}
		p.event = "message"
		p.data.Reset()
		return nil
	}
	if strings.HasPrefix(line, ":") {
		return nil
	}
	name, content, _ := strings.Cut(line, ":")
	content = strings.TrimPrefix(content, " ")
	switch name {
	case "event":
		p.event = content
	case "data":
		p.data.WriteString(content)
		p.data.WriteByte('\n')
		if p.data.Len() > maxEventLength {
			return errTooLarge
		}
	}
	return nil
}

// versionStream holds GET /v1/stream open on its own goroutine and reconnects
// with backoff.
type versionStream struct {
	client       *http.Client
	url          string
	headers      map[string]string
	t            timings
	debug        debugLog
	onVersion    func(int64)
	onOpen       func()
	onDisconnect func()

	ctx      context.Context
	cancel   context.CancelFunc
	done     chan struct{}
	once     sync.Once
	attempts int
}

func startVersionStream(s *versionStream) *versionStream {
	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.done = make(chan struct{})
	go s.run()
	return s
}

func (s *versionStream) close() {
	s.once.Do(func() {
		s.debug.log("Stream closed")
		s.cancel()
	})
}

func (s *versionStream) run() {
	defer close(s.done)
	for s.ctx.Err() == nil {
		failure := s.connect()
		if s.ctx.Err() != nil {
			return
		}
		s.onDisconnect()
		if IsPermanent(failure) {
			// Polling remains available; the next ruleset request also checks the key.
			logger.Printf("Flagbearer stream stopped; using polling: %v", failure)
			return
		}
		delay := backoff(s.attempts, s.t.retryBase, s.t.maxRetry)
		s.attempts++
		var refresh *refreshError
		if errors.As(failure, &refresh) {
			delay = max(delay, refresh.retryAfter)
		}
		s.debug.log("Stream reconnect scheduled", field{"delayMs", delay.Milliseconds()})
		timer := time.NewTimer(delay)
		select {
		case <-timer.C:
		case <-s.ctx.Done():
			timer.Stop()
			return
		}
	}
}

func (s *versionStream) connect() error {
	s.debug.log("Stream connecting")
	ctx, cancel := context.WithCancel(s.ctx)
	defer cancel()
	// One timer covers both limits: connecting, then each quiet stretch.
	deadline := time.AfterFunc(s.t.streamConnectTimeout, cancel)
	defer deadline.Stop()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, s.url, nil)
	if err != nil {
		return &refreshError{message: err.Error()}
	}
	for name, value := range s.headers {
		request.Header.Set(name, value)
	}
	request.Header.Set("Accept", "text/event-stream")
	response, err := s.client.Do(request)
	if err != nil {
		return &refreshError{message: "Flagbearer stream failed: " + err.Error(), retryable: true}
	}
	defer response.Body.Close()
	if err := checkStatus(response); err != nil {
		return err
	}
	if kind, _, _ := mime.ParseMediaType(response.Header.Get("Content-Type")); kind != "text/event-stream" {
		return &refreshError{message: "Invalid Flagbearer stream response"}
	}
	// Heartbeats arrive every 15 seconds, so silence past the idle limit means a dead connection.
	deadline.Reset(s.t.streamIdleTimeout)
	s.onOpen()

	parser := newEventParser(s.onVersion)
	buffer := make([]byte, 16_384)
	for {
		n, err := response.Body.Read(buffer)
		if n > 0 {
			deadline.Reset(s.t.streamIdleTimeout)
			s.attempts = 0
			if parseErr := parser.feed(bytes.Clone(buffer[:n])); parseErr != nil {
				return parseErr
			}
		}
		if err != nil {
			return err
		}
	}
}
