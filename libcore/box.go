package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/experimental/libbox/platform"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/conntrack"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var (
	// mainInstanceAccess guards mainInstance and protectCloser. Lock order: BoxInstance.access
	// first, then mainInstanceAccess.
	mainInstanceAccess sync.Mutex
	mainInstance       *BoxInstance
)

func getMainInstance() *BoxInstance {
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	return mainInstance
}

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	// a panic here would crash the app through JNI
	defer device.DeferPanicToError("ResetAllConnections", func(err error) { log.Println(err) })
	if system {
		// hold the lock so that the instance is not closed while its network is reset
		mainInstanceAccess.Lock()
		defer mainInstanceAccess.Unlock()
		if mainInstance != nil && mainInstance.Box != nil {
			// close tracked connections, and also let outbounds (mux / QUIC based
			// protocols such as hysteria2 and tuic) and DNS transports drop their
			// cached connections, which may be dead after sleep or network change
			mainInstance.Box.Router().ResetNetwork()
		} else {
			conntrack.Close()
		}
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager

	// users counts the URL tests running on this instance, so that Close does not close the
	// box under them. Guarded by usersAccess, not access, so a test never blocks on Close.
	usersAccess sync.Mutex
	users       int
	closing     bool
	usersDone   chan struct{}
}

// closeWaitTimeout bounds how long Close waits for running URL tests.
var closeWaitTimeout = 2 * time.Second

// acquire marks a use of the instance; it fails once Close has started. Call release after.
func (b *BoxInstance) acquire() bool {
	b.usersAccess.Lock()
	defer b.usersAccess.Unlock()
	if b.closing {
		return false
	}
	b.users++
	return true
}

func (b *BoxInstance) release() {
	b.usersAccess.Lock()
	defer b.usersAccess.Unlock()
	b.users--
	if b.users == 0 && b.usersDone != nil {
		close(b.usersDone)
		b.usersDone = nil
	}
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[platform.Interface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// no new users from now on; wait for the running ones below
	b.usersAccess.Lock()
	b.closing = true
	var usersDone chan struct{}
	if b.users > 0 {
		usersDone = make(chan struct{})
		b.usersDone = usersDone
	}
	b.usersAccess.Unlock()

	// clear main instance
	mainInstanceAccess.Lock()
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}
	mainInstanceAccess.Unlock()

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if usersDone != nil {
		select {
		case <-usersDone:
		case <-time.After(closeWaitTimeout):
			log.Println("box.Close: URL tests still running, closing anyway")
		}
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

// Sleep and Wake come from the idle broadcast, which may race with Close. A panic here would
// crash the app through JNI, so only act on a started instance and recover.
func (b *BoxInstance) Sleep() {
	b.access.Lock()
	defer b.access.Unlock()
	defer device.DeferPanicToError("box.Sleep", func(err error) { log.Println(err) })
	if b.state == 1 && b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	b.access.Lock()
	defer b.access.Unlock()
	defer device.DeferPanicToError("box.Wake", func(err error) { log.Println(err) })
	if b.state == 1 && b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		if !i.acquire() {
			return 0, errors.New("instance closed")
		}
		defer i.release()
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// read once, Close may clear it meanwhile
	main := getMainInstance()
	// test direct
	if main == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// the instance may have been closed after it was read; keep it open until the test ends
	if !main.acquire() {
		return 0, errors.New("instance closed")
	}
	defer main.release()
	// test mainInstance
	if main.v2api != nil {
		connectionTracker = main.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(main.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}

var protectCloser io.Closer

// goServeProtect must be called with mainInstanceAccess held.
func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
