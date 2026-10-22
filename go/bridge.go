// Package main is libtether.so: the engine (github.com/freeyoung/tether-core-go)
// for the Android app. bridge.go has no cgo, so its tests run on any OS;
// export_android.go and jni_android.c make the JNI layer over it.
//
// The app talks to a Bridge with JSON. It calls Call with an operation, and it
// reads events with Next on a thread of its own. The engine never calls into
// Java, so no Go thread needs a JNIEnv.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"

	"github.com/freeyoung/tether-core-go/core"
)

// Config is what the app passes to Start.
type Config struct {
	SupportDir string `json:"supportDir"`
	// Received files go here. The app moves each one to the system's Downloads.
	DownloadsDir string `json:"downloadsDir"`
	// Clipboard images from peers go here, for the app's FileProvider.
	ClipboardDir string `json:"clipboardDir"`
	DeviceName   string `json:"deviceName"`
	Port         int    `json:"port"`
	// The app gives the phone's text messages to computers that keep them:
	// it answers sms_requested with sms_batch.
	ProvideMessages bool `json:"provideMessages"`
}

// Bridge is one running engine.
type Bridge struct {
	engine    *core.Engine
	clipboard *memoryClipboard
	discovery *relayDiscovery
	events    *eventQueue
}

func Start(cfg Config, log func(string)) (*Bridge, error) {
	if cfg.SupportDir == "" || cfg.DownloadsDir == "" || cfg.ClipboardDir == "" {
		return nil, errors.New("supportDir, downloadsDir and clipboardDir are required")
	}
	b := &Bridge{events: newEventQueue()}
	b.clipboard = &memoryClipboard{dir: cfg.ClipboardDir, onWrite: b.clipboardWritten, log: log}
	b.discovery = &relayDiscovery{emit: b.emit}
	engine, err := core.NewEngine(core.Config{
		SupportDir: cfg.SupportDir, DownloadsDir: cfg.DownloadsDir, DeviceName: cfg.DeviceName, Port: cfg.Port, ProvideMessages: cfg.ProvideMessages, Log: log,
	}, b.clipboard, b.discovery)
	if err != nil {
		return nil, err
	}
	b.engine = engine
	engine.Delegate = delegate{b}
	engine.Start()
	b.pushState()
	return b, nil
}

// Stop closes every session and ends Next.
func (b *Bridge) Stop() {
	b.engine.Stop()
	b.events.close()
}

// Next blocks until there is an event, and returns false once the bridge is stopped.
func (b *Bridge) Next() ([]byte, bool) { return b.events.next() }

type args struct {
	Fingerprint string           `json:"fingerprint"`
	Host        string           `json:"host"`
	Port        int              `json:"port"`
	Paths       []string         `json:"paths"`
	Text        string           `json:"text"`
	Path        string           `json:"path"`
	Services    []discoveredJSON `json:"services"`
	// post_notification and remove_notification.
	Notification notificationJSON `json:"notification"`
	ID           string           `json:"id"`
	// sms_batch.
	Messages []core.SMS `json:"messages"`
	More     bool       `json:"more"`
}

// notificationJSON is a notification on this phone, as NotificationMirror
// reads it.
type notificationJSON struct {
	ID      string `json:"id"`
	App     string `json:"app"`
	AppName string `json:"appName"`
	Title   string `json:"title"`
	Text    string `json:"text"`
	Time    int64  `json:"time"`
}

// Call runs one operation and returns its result as JSON.
func (b *Bridge) Call(op string, raw []byte) ([]byte, error) {
	var a args
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &a); err != nil {
			return nil, fmt.Errorf("%s: %w", op, err)
		}
	}
	e := b.engine
	switch op {
	case "snapshot":
		return json.Marshal(snapshotOf(e.SnapshotNow()))
	case "version":
		return json.Marshal(map[string]string{"version": core.EngineVersion(), "minPeerVersion": core.MinPeerVersion})
	case "approve":
		e.Approve(a.Fingerprint)
	case "reject":
		e.Reject(a.Fingerprint)
	case "forget":
		e.Forget(a.Fingerprint)
	case "pair_host":
		if a.Port == 0 {
			a.Port = core.DefaultPort
		}
		e.PairWithComputer(a.Host, a.Port)
	case "pair_discovered":
		e.PairWithDiscovered(a.Fingerprint)
	case "cancel_pairing":
		e.CancelOutboundPairing()
	case "send_files":
		e.SendFiles(a.Paths, a.Fingerprint)
	case "network_changed":
		e.NetworkDidChange()
	case "copy_text":
		b.clipboard.userCopies(core.TextContent(a.Text))
		e.CheckLocalClipboard()
	case "copy_png":
		png, err := os.ReadFile(a.Path)
		if err != nil {
			return nil, err
		}
		b.clipboard.userCopies(core.PNGContent(png))
		e.CheckLocalClipboard()
	case "post_notification":
		n := a.Notification
		e.PostNotification(core.Notification{ID: n.ID, App: n.App, AppName: n.AppName, Title: n.Title, Text: n.Text, Time: n.Time})
	case "remove_notification":
		e.RemoveNotification(a.ID)
	case "sms_batch":
		e.SendMessages(a.Fingerprint, a.Messages, a.More)
	case "sms_changed":
		e.MessagesChanged()
	case "discovered":
		list := make([]core.Discovered, 0, len(a.Services))
		for _, s := range a.Services {
			list = append(list, core.Discovered{Name: s.Name, Fingerprint: s.Fingerprint, Hosts: s.Hosts, Port: s.Port, IsComputer: s.IsComputer})
		}
		b.discovery.found(list)
	default:
		return nil, fmt.Errorf("unknown operation %q", op)
	}
	return []byte("{}"), nil
}

func (b *Bridge) emit(event string, fields map[string]any) {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["event"] = event
	line, err := json.Marshal(fields)
	if err != nil {
		return
	}
	b.events.push(line)
}

// pushState sends a new snapshot. Only the newest one waits in the queue.
func (b *Bridge) pushState() {
	b.engine.Snapshot(func(s core.Snapshot) {
		line, err := json.Marshal(map[string]any{"event": "state", "snapshot": snapshotOf(s)})
		if err == nil {
			b.events.setState(line)
		}
	})
}

func (b *Bridge) clipboardWritten(c core.Content, pngPath string) {
	if c.IsPNG {
		if pngPath != "" {
			b.emit("clipboard", map[string]any{"png": pngPath})
		}
		return
	}
	b.emit("clipboard", map[string]any{"text": c.Text})
}

// MARK: - Engine delegate

type delegate struct{ b *Bridge }

func (d delegate) PairingRequested(r core.PairingRequest) {
	d.b.emit("pairing_requested", map[string]any{"fingerprint": r.Fingerprint, "name": r.Name, "kind": string(r.SuggestedKind)})
}

func (d delegate) PairingResolved(fp string) {
	d.b.emit("pairing_resolved", map[string]any{"fingerprint": fp})
}

func (d delegate) OutboundPairing(r core.OutboundResult) {
	d.b.emit("outbound", map[string]any{"kind": r.Kind, "peer": r.Peer, "reason": r.Reason})
}

func (d delegate) OpenURL(u string) { d.b.emit("open_url", map[string]any{"url": u}) }

func (d delegate) FileReceived(path, peer string) {
	d.b.emit("file_received", map[string]any{"path": path, "peer": peer})
}

func (d delegate) FileSent(name, peer string, ok bool, reason string) {
	d.b.emit("file_sent", map[string]any{"name": name, "peer": peer, "ok": ok, "reason": reason})
}

func (d delegate) Failed(message string) { d.b.emit("failed", map[string]any{"message": message}) }

func (d delegate) StateChanged() { d.b.pushState() }

func (d delegate) PeerOutdated(name, version string) {
	d.b.emit("peer_outdated", map[string]any{"name": name, "version": version})
}

// The phone sends notifications; it does not show those of others.
func (d delegate) NotificationPosted(core.Notification) {}

func (d delegate) NotificationRemoved(string, string) {}

// MessagesRequested: the app answers with sms_batch, the messages from the
// date since on, the oldest first.
func (d delegate) MessagesRequested(fp string, since int64) {
	d.b.emit("sms_requested", map[string]any{"fingerprint": fp, "since": since})
}

// The phone gives messages; it does not keep those of others.
func (d delegate) MessagesAdded(string, []core.SMS) {}

// MARK: - Clipboard

// memoryClipboard holds what the user last copied or a peer last sent. An
// Android app in the background cannot read the system clipboard, so the app
// tells the engine what the user copies (copy_text, copy_png), and writes
// the system clipboard when an event says so.
type memoryClipboard struct {
	mu      sync.Mutex
	count   uint64
	content core.Content
	has     bool
	dir     string
	onWrite func(core.Content, string)
	log     func(string)
}

func (c *memoryClipboard) ChangeCount() uint64 { c.mu.Lock(); defer c.mu.Unlock(); return c.count }

func (c *memoryClipboard) Read() (core.Content, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.content, c.has
}

func (c *memoryClipboard) Write(v core.Content) uint64 {
	c.mu.Lock()
	c.content, c.has = v, true
	c.count++
	count := c.count
	c.mu.Unlock()
	path := ""
	if v.IsPNG {
		var err error
		if path, err = c.savePNG(v.PNG, count); err != nil && c.log != nil {
			c.log("could not save a clipboard image: " + err.Error())
		}
	}
	c.onWrite(v, path)
	return count
}

func (c *memoryClipboard) userCopies(v core.Content) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.content, c.has = v, true
	c.count++
}

// keptImages: an app may paste an image some time after the clipboard has a
// newer one, and the app lists recent images, so older files stay a while.
const keptImages = 10

func (c *memoryClipboard) savePNG(png []byte, count uint64) (string, error) {
	if err := os.MkdirAll(c.dir, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(c.dir, fmt.Sprintf("clipboard-%d.png", count))
	if err := os.WriteFile(path, png, 0o600); err != nil {
		return "", err
	}
	old, _ := filepath.Glob(filepath.Join(c.dir, "clipboard-*.png"))
	sort.Slice(old, func(i, j int) bool { return imageNumber(old[i]) < imageNumber(old[j]) })
	for len(old) > keptImages {
		os.Remove(old[0])
		old = old[1:]
	}
	return path, nil
}

func imageNumber(path string) uint64 {
	var n uint64
	fmt.Sscanf(filepath.Base(path), "clipboard-%d.png", &n)
	return n
}

// MARK: - Discovery

// relayDiscovery leaves DNS-SD to the app, which has NsdManager. Go's own
// multicast cannot list the interfaces on Android 11 and later.
type relayDiscovery struct {
	mu        sync.Mutex
	onResults func([]core.Discovered)
	emit      func(string, map[string]any)
}

func (d *relayDiscovery) Start(instance string, port int, fingerprint string, onResults func([]core.Discovered)) {
	d.mu.Lock()
	d.onResults = onResults
	d.mu.Unlock()
	d.emit("discovery_start", map[string]any{"instance": instance, "port": port, "fingerprint": fingerprint})
}

func (d *relayDiscovery) Stop() {
	d.mu.Lock()
	d.onResults = nil
	d.mu.Unlock()
	d.emit("discovery_stop", nil)
}

func (d *relayDiscovery) found(list []core.Discovered) {
	d.mu.Lock()
	f := d.onResults
	d.mu.Unlock()
	if f != nil {
		f(list)
	}
}

// MARK: - Events

// eventQueue never blocks the engine: events wait in a list, and only the
// newest state snapshot is kept.
type eventQueue struct {
	mu     sync.Mutex
	cond   *sync.Cond
	items  [][]byte
	state  []byte
	closed bool
}

func newEventQueue() *eventQueue {
	q := &eventQueue{}
	q.cond = sync.NewCond(&q.mu)
	return q
}

func (q *eventQueue) push(line []byte) {
	q.mu.Lock()
	q.items = append(q.items, line)
	q.mu.Unlock()
	q.cond.Signal()
}

func (q *eventQueue) setState(line []byte) {
	q.mu.Lock()
	q.state = line
	q.mu.Unlock()
	q.cond.Signal()
}

func (q *eventQueue) next() ([]byte, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for !q.closed && len(q.items) == 0 && q.state == nil {
		q.cond.Wait()
	}
	switch {
	case q.closed:
		return nil, false
	case len(q.items) > 0:
		line := q.items[0]
		q.items[0] = nil
		q.items = q.items[1:]
		return line, true
	}
	line := q.state
	q.state = nil
	return line, true
}

func (q *eventQueue) close() {
	q.mu.Lock()
	q.closed = true
	q.mu.Unlock()
	q.cond.Broadcast()
}

// MARK: - JSON

type peerJSON struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	Kind        string `json:"kind"`
	Connected   bool   `json:"connected"`
}

type requestJSON struct {
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	Kind        string `json:"kind"`
}

type discoveredJSON struct {
	Name        string   `json:"name"`
	Fingerprint string   `json:"fingerprint"`
	Hosts       []string `json:"hosts"`
	Port        int      `json:"port"`
	IsComputer  bool     `json:"isComputer"`
}

type transferJSON struct {
	Filename string  `json:"filename"`
	Peer     string  `json:"peer"`
	Fraction float64 `json:"fraction"`
	Queued   int     `json:"queued"`
}

type snapshotJSON struct {
	Fingerprint string           `json:"fingerprint"`
	Listening   bool             `json:"listening"`
	Peers       []peerJSON       `json:"peers"`
	Pending     []requestJSON    `json:"pending"`
	Unpaired    []discoveredJSON `json:"unpaired"`
	Outgoing    []string         `json:"outgoing"`
	Sending     []transferJSON   `json:"sending"`
}

func snapshotOf(s core.Snapshot) snapshotJSON {
	out := snapshotJSON{
		Fingerprint: s.Fingerprint, Listening: s.Listening,
		Peers: []peerJSON{}, Pending: []requestJSON{}, Unpaired: []discoveredJSON{}, Outgoing: []string{}, Sending: []transferJSON{},
	}
	for _, p := range s.Peers {
		out.Peers = append(out.Peers, peerJSON{p.Fingerprint, p.Name, string(p.Kind), p.Connected})
	}
	for _, r := range s.Pending {
		out.Pending = append(out.Pending, requestJSON{r.Fingerprint, r.Name, string(r.SuggestedKind)})
	}
	for _, d := range s.Unpaired {
		out.Unpaired = append(out.Unpaired, discoveredJSON{d.Name, d.Fingerprint, d.Hosts, d.Port, d.IsComputer})
	}
	out.Outgoing = append(out.Outgoing, s.Outgoing...)
	for _, t := range s.Sending {
		out.Sending = append(out.Sending, transferJSON{t.Filename, t.Peer, t.Fraction, t.Queued})
	}
	return out
}
