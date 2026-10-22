package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/freeyoung/tether-core-go/core"
)

// desktop is a plain engine, like Tether Desktop on a computer.
type desktop struct {
	engine    *core.Engine
	clipboard *memoryClipboard
	mu        sync.Mutex
	writes    []core.Content
	received  []string
	posted    []core.Notification
	removed   []string
	approve   bool
}

func (d *desktop) PairingRequested(r core.PairingRequest) {
	d.mu.Lock()
	approve := d.approve
	d.mu.Unlock()
	if approve {
		d.engine.Approve(r.Fingerprint)
	}
}
func (d *desktop) PairingResolved(string)                {}
func (d *desktop) OutboundPairing(core.OutboundResult)   {}
func (d *desktop) OpenURL(string)                        {}
func (d *desktop) Failed(string)                         {}
func (d *desktop) StateChanged()                         {}
func (d *desktop) PeerOutdated(string, string)           {}
func (d *desktop) FileSent(string, string, bool, string) {}
func (d *desktop) NotificationPosted(n core.Notification) {
	d.mu.Lock()
	d.posted = append(d.posted, n)
	d.mu.Unlock()
}
func (d *desktop) NotificationRemoved(_, id string) {
	d.mu.Lock()
	d.removed = append(d.removed, id)
	d.mu.Unlock()
}
func (d *desktop) FileReceived(path, _ string) {
	d.mu.Lock()
	d.received = append(d.received, path)
	d.mu.Unlock()
}
func (d *desktop) lastWrite() (c core.Content, ok bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.writes) == 0 {
		return c, false
	}
	return d.writes[len(d.writes)-1], true
}
func (d *desktop) receivedFiles() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return append([]string{}, d.received...)
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func newDesktop(t *testing.T) (*desktop, int) {
	t.Helper()
	root := t.TempDir()
	d := &desktop{}
	d.clipboard = &memoryClipboard{dir: filepath.Join(root, "clip"), onWrite: func(c core.Content, _ string) {
		d.mu.Lock()
		d.writes = append(d.writes, c)
		d.mu.Unlock()
	}}
	port := freePort(t)
	e, err := core.NewEngine(core.Config{
		SupportDir: filepath.Join(root, "support"), DownloadsDir: filepath.Join(root, "downloads"),
		DeviceName: "Desk", Port: port, ShowNotifications: true, Log: func(s string) { t.Log("desk: " + s) },
	}, d.clipboard, nil)
	if err != nil {
		t.Fatal(err)
	}
	e.Delegate = d
	d.engine = e
	e.Start()
	t.Cleanup(e.Stop)
	return d, port
}

type event struct {
	Event       string        `json:"event"`
	Fingerprint string        `json:"fingerprint"`
	Name        string        `json:"name"`
	Kind        string        `json:"kind"`
	Text        string        `json:"text"`
	PNG         string        `json:"png"`
	Path        string        `json:"path"`
	Peer        string        `json:"peer"`
	Instance    string        `json:"instance"`
	Snapshot    *snapshotJSON `json:"snapshot"`
}

// phone runs a Bridge and collects its events, as the app's event thread does.
type phone struct {
	bridge *Bridge
	mu     sync.Mutex
	events []event
	root   string
	port   int
}

func newPhone(t *testing.T) *phone {
	t.Helper()
	root := t.TempDir()
	p := &phone{root: root, port: freePort(t)}
	b, err := Start(Config{
		SupportDir: filepath.Join(root, "support"), DownloadsDir: filepath.Join(root, "received"),
		ClipboardDir: filepath.Join(root, "clipboard"), DeviceName: "Pixel", Port: p.port,
	}, func(s string) { t.Log("phone: " + s) })
	if err != nil {
		t.Fatal(err)
	}
	p.bridge = b
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			line, ok := b.Next()
			if !ok {
				return
			}
			var e event
			if err := json.Unmarshal(line, &e); err != nil {
				t.Errorf("bad event %s: %v", line, err)
				continue
			}
			p.mu.Lock()
			p.events = append(p.events, e)
			p.mu.Unlock()
		}
	}()
	t.Cleanup(func() {
		b.Stop()
		<-done
	})
	return p
}

func (p *phone) waitFor(t *testing.T, match func(event) bool) event {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		for _, e := range p.events {
			if match(e) {
				p.mu.Unlock()
				return e
			}
		}
		p.mu.Unlock()
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("no matching event")
	return event{}
}

func (p *phone) call(t *testing.T, op string, a any) []byte {
	t.Helper()
	raw, _ := json.Marshal(a)
	out, err := p.bridge.Call(op, raw)
	if err != nil {
		t.Fatalf("%s: %v", op, err)
	}
	return out
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("timed out: " + what)
}

// pair has the desktop ask the phone to pair, and the phone approve, as with
// Pair with a Device > Enter Address on Tether Desktop.
func pair(t *testing.T, p *phone, d *desktop) {
	t.Helper()
	d.engine.PairWithComputer("127.0.0.1", p.port)
	r := p.waitFor(t, func(e event) bool { return e.Event == "pairing_requested" })
	if r.Name != "Desk" || r.Kind != "computer" {
		t.Fatalf("request %+v", r)
	}
	p.call(t, "approve", map[string]string{"fingerprint": r.Fingerprint})
	p.waitFor(t, func(e event) bool {
		return e.Event == "state" && e.Snapshot != nil && len(e.Snapshot.Peers) == 1 && e.Snapshot.Peers[0].Connected
	})
	eventually(t, "desktop session", func() bool {
		s := d.engine.SnapshotNow()
		return len(s.Peers) == 1 && s.Peers[0].Connected
	})
}

func TestDiscoveryStartsWithTheEngine(t *testing.T) {
	p := newPhone(t)
	e := p.waitFor(t, func(e event) bool { return e.Event == "discovery_start" })
	if e.Instance != "Pixel" || e.Fingerprint != p.bridge.engine.Identity.Fingerprint {
		t.Fatalf("discovery_start %+v", e)
	}
}

func TestClipboardBothWays(t *testing.T) {
	p := newPhone(t)
	d, _ := newDesktop(t)
	pair(t, p, d)

	// The desktop copies: the phone's app gets the text to put on the system clipboard.
	text := "héllo 👋 from the desk"
	d.clipboard.userCopies(core.TextContent(text))
	d.engine.CheckLocalClipboard()
	p.waitFor(t, func(e event) bool { return e.Event == "clipboard" && e.Text == text })

	// The user copies on the phone.
	p.call(t, "copy_text", map[string]string{"text": "from the phone"})
	eventually(t, "desktop clipboard", func() bool {
		c, ok := d.lastWrite()
		return ok && c.Text == "from the phone"
	})
}

func TestClipboardImageToPhone(t *testing.T) {
	p := newPhone(t)
	d, _ := newDesktop(t)
	pair(t, p, d)

	png := append([]byte("\x89PNG\r\n\x1a\n"), bytes.Repeat([]byte{7}, 300_000)...)
	d.clipboard.userCopies(core.PNGContent(png))
	d.engine.CheckLocalClipboard()
	e := p.waitFor(t, func(e event) bool { return e.Event == "clipboard" && e.PNG != "" })
	got, err := os.ReadFile(e.PNG)
	if err != nil || !bytes.Equal(got, png) {
		t.Fatalf("image file %s: %v", e.PNG, err)
	}

	// And an image the user copies on the phone.
	path := filepath.Join(p.root, "copied.png")
	other := append([]byte("\x89PNG\r\n\x1a\n"), bytes.Repeat([]byte{9}, 1000)...)
	os.WriteFile(path, other, 0o600)
	p.call(t, "copy_png", map[string]string{"path": path})
	eventually(t, "desktop image", func() bool {
		c, ok := d.lastWrite()
		return ok && c.IsPNG && bytes.Equal(c.PNG, other)
	})
}

func TestFilesBothWays(t *testing.T) {
	p := newPhone(t)
	d, _ := newDesktop(t)
	pair(t, p, d)
	phoneFP := p.bridge.engine.Identity.Fingerprint
	deskFP := d.engine.Identity.Fingerprint

	src := filepath.Join(t.TempDir(), "notes.txt")
	os.WriteFile(src, []byte("some notes"), 0o600)
	d.engine.SendFiles([]string{src}, phoneFP)
	e := p.waitFor(t, func(e event) bool { return e.Event == "file_received" })
	if got, _ := os.ReadFile(e.Path); string(got) != "some notes" || e.Peer != "Desk" {
		t.Fatalf("received %+v", e)
	}

	out := filepath.Join(t.TempDir(), "photo.jpg")
	os.WriteFile(out, []byte("jpeg bytes"), 0o600)
	p.call(t, "send_files", map[string]any{"fingerprint": deskFP, "paths": []string{out}})
	p.waitFor(t, func(e event) bool { return e.Event == "file_sent" })
	eventually(t, "desktop file", func() bool { return len(d.receivedFiles()) == 1 })
	if got, _ := os.ReadFile(d.receivedFiles()[0]); string(got) != "jpeg bytes" {
		t.Fatalf("desktop got %q", got)
	}
}

func TestNotificationsGoToTheDesktop(t *testing.T) {
	p := newPhone(t)
	d, _ := newDesktop(t)
	d.approve = true
	pair(t, p, d)
	n := map[string]any{"id": "0|com.chat|7|null|10123", "app": "com.chat", "appName": "Chat", "title": "Alice", "text": "Dinner? 🍜", "time": 1792650000000}
	// The desktop's hello may still be on its way: post until it arrives.
	eventually(t, "notification", func() bool {
		p.call(t, "post_notification", map[string]any{"notification": n})
		d.mu.Lock()
		defer d.mu.Unlock()
		return len(d.posted) > 0
	})
	d.mu.Lock()
	got := d.posted[0]
	d.mu.Unlock()
	if got.ID != "0|com.chat|7|null|10123" || got.App != "com.chat" || got.AppName != "Chat" || got.Title != "Alice" ||
		got.Text != "Dinner? 🍜" || got.Time != 1792650000000 || got.PeerName != "Pixel" {
		t.Fatalf("%+v", got)
	}
	p.call(t, "remove_notification", map[string]string{"id": "0|com.chat|7|null|10123"})
	eventually(t, "removal", func() bool {
		d.mu.Lock()
		defer d.mu.Unlock()
		return len(d.removed) == 1 && d.removed[0] == "0|com.chat|7|null|10123"
	})
}

func TestPhonePairsWithDiscoveredComputer(t *testing.T) {
	p := newPhone(t)
	d, port := newDesktop(t)
	fp := d.engine.Identity.Fingerprint
	p.call(t, "discovered", map[string]any{"services": []discoveredJSON{
		{Name: "Desk", Fingerprint: fp, Hosts: []string{"127.0.0.1"}, Port: port, IsComputer: true},
	}})
	p.waitFor(t, func(e event) bool {
		return e.Event == "state" && e.Snapshot != nil && len(e.Snapshot.Unpaired) == 1
	})

	// The desktop approves every request from now on.
	d.mu.Lock()
	d.approve = true
	d.mu.Unlock()
	p.call(t, "pair_discovered", map[string]string{"fingerprint": fp})
	p.waitFor(t, func(e event) bool {
		return e.Event == "state" && e.Snapshot != nil && len(e.Snapshot.Peers) == 1 &&
			e.Snapshot.Peers[0].Connected && e.Snapshot.Peers[0].Kind == "computer"
	})
}

func TestUnknownOperation(t *testing.T) {
	p := newPhone(t)
	if _, err := p.bridge.Call("nope", nil); err == nil {
		t.Fatal("no error")
	}
}

func TestImagesArePruned(t *testing.T) {
	dir := t.TempDir()
	c := &memoryClipboard{dir: dir, onWrite: func(core.Content, string) {}}
	for range keptImages + 3 {
		c.Write(core.PNGContent([]byte("\x89PNG\r\n\x1a\n")))
	}
	files, _ := filepath.Glob(filepath.Join(dir, "*.png"))
	newest := fmt.Sprintf("clipboard-%d.png", keptImages+3)
	if len(files) != keptImages || !slices.Contains(files, filepath.Join(dir, newest)) || slices.Contains(files, filepath.Join(dir, "clipboard-3.png")) {
		t.Fatalf("files %v", files)
	}
}

func TestStateIsCoalesced(t *testing.T) {
	q := newEventQueue()
	q.setState([]byte("1"))
	q.push([]byte("a"))
	q.setState([]byte("2"))
	var got []string
	for range 2 {
		line, _ := q.next()
		got = append(got, string(line))
	}
	if got[0] != "a" || got[1] != "2" {
		t.Fatalf("got %v", got)
	}
	q.close()
	if _, ok := q.next(); ok {
		t.Fatal("next after close")
	}
}

// The engine reads its version from the build information: the version of
// tether-core-go in go.mod, not the version for a local copy.
func TestEngineVersion(t *testing.T) {
	p := newPhone(t)
	var v struct{ Version string }
	if err := json.Unmarshal(p.call(t, "version", nil), &v); err != nil {
		t.Fatal(err)
	}
	if v.Version == "" || v.Version == "0.0.0-dev" {
		t.Fatalf("engine version %q", v.Version)
	}
}
