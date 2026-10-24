package libcore

import (
	"net"
	"testing"

	"github.com/sagernet/sing/common/control"
)

func TestAndroidInterfaceMonitorTracksHandoverAndLoss(t *testing.T) {
	interfaces, err := net.Interfaces()
	if err != nil || len(interfaces) == 0 {
		t.Skip("no local interface available")
	}
	name := interfaces[0].Name
	UpdateDefaultInterface(name, true)
	t.Cleanup(func() { UpdateDefaultInterface("", true) })

	monitor := &androidInterfaceMonitor{platform: &boxPlatformInterfaceWrapper{}}
	var updates []string
	monitor.RegisterCallback(func(iif *control.Interface, flags int) {
		if iif == nil {
			updates = append(updates, "")
		} else {
			updates = append(updates, iif.Name)
		}
	})
	if err := monitor.Start(); err != nil {
		t.Fatal(err)
	}
	if got := monitor.DefaultInterface(); got == nil || got.Name != name {
		t.Fatalf("initial default interface = %v, want %s", got, name)
	}
	UpdateDefaultInterface("", true)
	if monitor.DefaultInterface() != nil {
		t.Fatal("lost network did not clear default interface")
	}
	UpdateDefaultInterface(name, true)
	if UpdateDefaultInterface("", false) {
		t.Fatal("silent update reported a notification")
	}
	if monitor.DefaultInterface() != nil {
		t.Fatal("silent update did not change the stored interface")
	}
	if len(updates) != 3 {
		t.Fatalf("silent update sent a callback: %v", updates)
	}
	if err := monitor.Close(); err != nil {
		t.Fatal(err)
	}
	UpdateDefaultInterface("", true)
	if got, want := len(updates), 3; got != want {
		t.Fatalf("callbacks = %v, want %d updates", updates, want)
	}
	if updates[0] != name || updates[1] != "" || updates[2] != name {
		t.Fatalf("unexpected callback sequence: %v", updates)
	}
}
