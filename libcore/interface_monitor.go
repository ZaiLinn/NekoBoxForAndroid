package libcore

import (
	"log"
	"net"
	"sync"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// Android's ConnectivityManager chooses the upstream network. The core needs
// the same choice so its interface listeners and network pause state recover
// after a handover, without restarting the VPN service.
var androidInterfaces = struct {
	sync.Mutex
	name     string
	monitors map[*androidInterfaceMonitor]struct{}
}{monitors: make(map[*androidInterfaceMonitor]struct{})}

// Serialize startup, handovers and shutdown so a late initial snapshot cannot
// overwrite a newer Android network callback.
var androidInterfaceUpdate sync.Mutex

func currentAndroidInterface() string {
	androidInterfaces.Lock()
	defer androidInterfaces.Unlock()
	return androidInterfaces.name
}

// UpdateDefaultInterface is called from the Android network listener. An empty
// name means the physical network is gone.
func UpdateDefaultInterface(name string, notify bool) bool {
	androidInterfaceUpdate.Lock()
	defer androidInterfaceUpdate.Unlock()
	androidInterfaces.Lock()
	androidInterfaces.name = name
	monitors := make([]*androidInterfaceMonitor, 0, len(androidInterfaces.monitors))
	for monitor := range androidInterfaces.monitors {
		monitors = append(monitors, monitor)
	}
	androidInterfaces.Unlock()
	updated := false
	for _, monitor := range monitors {
		if monitor.update(name, notify) {
			updated = true
		}
	}
	return updated
}

type androidInterfaceMonitor struct {
	platform    *boxPlatformInterfaceWrapper
	mu          sync.Mutex
	current     *control.Interface
	callbacks   list.List[tun.DefaultInterfaceUpdateCallback]
	myInterface string
}

func (m *androidInterfaceMonitor) Start() error {
	androidInterfaceUpdate.Lock()
	defer androidInterfaceUpdate.Unlock()
	androidInterfaces.Lock()
	androidInterfaces.monitors[m] = struct{}{}
	name := androidInterfaces.name
	androidInterfaces.Unlock()
	m.update(name, true)
	return nil
}

func (m *androidInterfaceMonitor) Close() error {
	androidInterfaceUpdate.Lock()
	defer androidInterfaceUpdate.Unlock()
	androidInterfaces.Lock()
	delete(androidInterfaces.monitors, m)
	androidInterfaces.Unlock()
	return nil
}

func (m *androidInterfaceMonitor) update(name string, notify bool) bool {
	var next *control.Interface
	if name != "" {
		if iif, err := net.InterfaceByName(name); err == nil {
			if info, err := control.InterfaceFromNet(*iif); err == nil {
				next = &info
			}
		}
	}
	if m.platform.networkManager != nil {
		if err := m.platform.networkManager.UpdateInterfaces(); err != nil {
			log.Printf("update Android network interfaces: %v", err)
			return false // let the Android side fall back to its connection reset
		}
	}
	m.mu.Lock()
	old := m.current
	if old == nil && next == nil || old != nil && next != nil && old.Index == next.Index && old.Name == next.Name {
		m.mu.Unlock()
		return false
	}
	m.current = next
	callbacks := m.callbacks.Array()
	m.mu.Unlock()
	if notify {
		for _, callback := range callbacks {
			callback(next, 0)
		}
	}
	return notify && len(callbacks) > 0
}

func (m *androidInterfaceMonitor) DefaultInterface() *control.Interface {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.current
}

func (m *androidInterfaceMonitor) OverrideAndroidVPN() bool { return false }
func (m *androidInterfaceMonitor) AndroidVPNEnabled() bool  { return false }

func (m *androidInterfaceMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *androidInterfaceMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks.Remove(element)
}

func (m *androidInterfaceMonitor) RegisterMyInterface(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.myInterface = name
}

func (m *androidInterfaceMonitor) MyInterface() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.myInterface
}
