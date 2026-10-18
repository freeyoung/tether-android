package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>

extern void tetherLog(const char* message);
*/
import "C"

import (
	"encoding/json"
	"sync"
	"unsafe"
)

// The app holds a bridge by its handle, so that the event thread of a
// stopped bridge never reads the events of the next one.
var (
	bridgesMu  sync.Mutex
	bridges    = map[int64]*Bridge{}
	nextHandle int64
)

func bridgeOf(h int64) *Bridge {
	bridgesMu.Lock()
	defer bridgesMu.Unlock()
	return bridges[h]
}

func logLine(s string) {
	cs := C.CString(s)
	C.tetherLog(cs)
	C.free(unsafe.Pointer(cs))
}

// out copies b to C memory; jni_android.c frees it.
func out(b []byte, n *C.int) *C.char {
	*n = C.int(len(b))
	return (*C.char)(C.CBytes(b))
}

func errorJSON(err error) []byte {
	line, _ := json.Marshal(map[string]string{"error": err.Error()})
	return line
}

//export tetherStart
func tetherStart(config *C.char, configLen C.int, n *C.int) *C.char {
	var cfg Config
	if err := json.Unmarshal(C.GoBytes(unsafe.Pointer(config), configLen), &cfg); err != nil {
		return out(errorJSON(err), n)
	}
	b, err := Start(cfg, logLine)
	if err != nil {
		return out(errorJSON(err), n)
	}
	bridgesMu.Lock()
	nextHandle++
	h := nextHandle
	bridges[h] = b
	bridgesMu.Unlock()
	line, _ := json.Marshal(map[string]int64{"handle": h})
	return out(line, n)
}

//export tetherStop
func tetherStop(h int64) {
	bridgesMu.Lock()
	b := bridges[h]
	delete(bridges, h)
	bridgesMu.Unlock()
	if b != nil {
		b.Stop()
	}
}

// tetherNextEvent returns NULL when the bridge is stopped.
//
//export tetherNextEvent
func tetherNextEvent(h int64, n *C.int) *C.char {
	b := bridgeOf(h)
	if b == nil {
		return nil
	}
	line, ok := b.Next()
	if !ok {
		return nil
	}
	return out(line, n)
}

//export tetherCall
func tetherCall(h int64, op *C.char, opLen C.int, arg *C.char, argLen C.int, n *C.int) *C.char {
	b := bridgeOf(h)
	if b == nil {
		return out([]byte(`{"error":"Tether is not running"}`), n)
	}
	var raw []byte
	if argLen > 0 {
		raw = C.GoBytes(unsafe.Pointer(arg), argLen)
	}
	result, err := b.Call(C.GoStringN(op, opLen), raw)
	if err != nil {
		return out(errorJSON(err), n)
	}
	return out(result, n)
}
