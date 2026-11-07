package flagbearer

import (
	"fmt"
	"log"
	"os"
	"strings"
)

var logger = log.New(os.Stderr, "", log.LstdFlags)

// debugLog writes diagnostics when debug is on. It never logs keys, user data,
// flag names or rules.
type debugLog bool

// field is one detail in a debug line, kept in order.
type field struct {
	name  string
	value any
}

func (enabled debugLog) log(message string, fields ...field) {
	if !enabled {
		return
	}
	if len(fields) == 0 {
		logger.Printf("[Flagbearer] %s", message)
		return
	}
	parts := make([]string, len(fields))
	for index, item := range fields {
		parts[index] = fmt.Sprintf("%s: %v", item.name, item.value)
	}
	// Same shape as the Node SDK's console.debug output: { version: 42 }.
	logger.Printf("[Flagbearer] %s { %s }", message, strings.Join(parts, ", "))
}

// optional formats a missing number the way the Node SDK logs it.
func optional(value *int64) any {
	if value == nil {
		return "undefined"
	}
	return *value
}
